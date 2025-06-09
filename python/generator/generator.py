import random
import json
import os
import uuid
import threading
import time

from kafka import KafkaProducer
from datetime import datetime

from python.generator.transaction import Transaction
from python.generator.card_profile import CardProfile
from python.generator.card_generator import CardGenerator
from python.generator.anomaly import AnomalyGenerator
from python.generator.location_utils import LocationUtils

KAFKA_BROKER = os.getenv('KAFKA_BROKER', 'localhost:9092')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = os.getenv('REDIS_PORT', '6379')
REDIS_DB = os.getenv('REDIS_DB', '0')
REDIS_PASSWORD = os.getenv('REDIS_PASSWORD', 'admin')

normal_categories_weighted = [
    "grocery", "grocery", "grocery", "grocery",
    "restaurant", "restaurant", "restaurant",
    "gas_station", "gas_station", "gas_station",
    "retail", "retail", "retail",
    "pharmacy", "pharmacy",
    "transport", "transport",
    "supermarket", "supermarket",
    "entertainment", "cinema", "clothing",
    "electronics", "home_improvement", "bookstore",
    "coffee_shop", "fast_food", "department_store",
    "jewelry", "sports_goods", "beauty_salon",
    "auto_service", "hotel", "travel_agency",
    "insurance", "bank_service", "medical",
    "education", "charity", "parking","atm_withdrawal"
]
class TransactionGenerator:
    def __init__(self, num_cards: int = 10000, num_users: int = 2000):
        self.kafka_producer = KafkaProducer(
            bootstrap_servers=KAFKA_BROKER,
            value_serializer=lambda x: x.encode('utf-8'),
            key_serializer=lambda x: x.encode('utf-8') if x else None
        )

        self.num_cards = num_cards
        self.num_users = num_users

        self.max_threads = 100
        self.active_threads = 0

        self.user_lock = threading.Lock()
        self.users_in_plan = set()

        self.anomaly_generators = {'amount': AnomalyGenerator.amount_anomaly,
                                   'limit':AnomalyGenerator.insufficient_funds_anomaly,
                                   'expired_card':AnomalyGenerator.expired_card_anomaly,
                                   'not_active':AnomalyGenerator.not_active_card_anomaly,
                                   'transaction_limit': AnomalyGenerator.transaction_limit_anomaly,
                                   'micro_transactions': AnomalyGenerator.micro_transaction_anomaly,
                                   'rapid_geo_change': AnomalyGenerator.rapid_geo_change_anomaly,
                                   'duplicate_transactions': AnomalyGenerator.duplicate_transaction_anomaly,
                                   'round_amounts': AnomalyGenerator.round_amount_anomaly,
                                   'atm_pattern': AnomalyGenerator.atm_pattern_anomaly,
                                   'unusual_merchant_anomaly': AnomalyGenerator.unusual_merchant_anomaly
                                   }
        self.atm_amounts = [20.00, 50.00, 100.00, 200.00, 300.00, 500.00, 1000.00]
        self.card_generator = CardGenerator(REDIS_HOST, REDIS_PORT, REDIS_DB, REDIS_PASSWORD)
        self.card_ids = self.card_generator.get_all_card_ids()

    def send_to_kafka(self, transaction: Transaction):
        try:
            transaction_json = json.dumps({
                "transaction_id": transaction.transaction_id,
                "card_id": transaction.card_id,
                "user_id": transaction.user_id,
                "amount": transaction.amount,
                "status": transaction.status,
                "merchant_category": transaction.merchant_category,
                "location": {
                    "latitude": transaction.location.latitude,
                    "longitude": transaction.location.longitude,
                    "city": transaction.location.city,
                    "country": transaction.location.country,
                    "country_code": transaction.location.country_code
                }
            })

            self.kafka_producer.send(
                topic='transactions',
                key=transaction.card_id,
                value=transaction_json
            )

        except Exception as e:
            print(f"Failed to send transaction to Kafka: {e}")

    def generate_transaction(self, card_profile: CardProfile) -> Transaction:
        category = random.choice(normal_categories_weighted)

        if category == "atm_withdrawal":
            amount = random.choice(self.atm_amounts)
            max_allowed = min(card_profile.current_balance, card_profile.transaction_limit,
                              card_profile.daily_limit * 0.3)
            if amount > max_allowed:
                available_amounts = [amt for amt in self.atm_amounts if amt <= max_allowed]
                if available_amounts:
                    amount = random.choice(available_amounts)
                else:
                    amount = 20.00
        else:
            min_amount = max(5.0, card_profile.avg_amount - card_profile.std_amount)
            max_amount = card_profile.avg_amount + card_profile.std_amount
            amount = round(random.uniform(min_amount, max_amount), 2)
            max_allowed = round(card_profile.daily_limit * 0.1, 2)
            amount = min(amount, max_allowed)

        if random.random() < 0.85:
            location = LocationUtils.get_random_polish_city()
        else:
            location = LocationUtils.get_random_foreign_city()

        transaction = Transaction(
            transaction_id=f"tx_{uuid.uuid4().hex[:12]}",
            card_id=card_profile.card_id,
            user_id=card_profile.user_id,
            amount=amount,
            location=location,
            status="approved",
            merchant_category=category
        )

        return transaction


    def execute_plan(self, plan: dict):
        user_id = plan["user_id"]
        plan_type = plan["type"]

        try:
            with self.user_lock:
                self.users_in_plan.add(user_id)

            if plan_type == "rapid_geo_change":
                transaction_count = len(plan["locations"])
            else:
                transaction_count = plan["count"]

            for i in range(transaction_count):
                time.sleep(plan["intervals_seconds"][i])

                if plan_type == "rapid_geo_change":
                    location = plan["locations"][i]
                else:
                    location = plan["location"]

                if plan_type == "atm_pattern":
                    category = "atm_withdrawal"
                else:
                    category = random.choice(normal_categories_weighted)

                transaction = Transaction(
                    transaction_id=f"tx_{uuid.uuid4().hex[:12]}",
                    card_id=plan["card_id"],
                    user_id=plan["user_id"],
                    amount=plan["amounts"][i],
                    location=location,
                    status="approved",
                    merchant_category=category
                )

                print(f"[{plan_type.upper()} PLAN] Executing #{i + 1}/{transaction_count}: {plan['amounts'][i]}zł in {location.city}")
                self.send_to_kafka(transaction)

        finally:
            with self.user_lock:
                self.users_in_plan.discard(user_id)
                self.active_threads -= 1


    def inject_anomaly(self, transaction: Transaction, card_profile: CardProfile):
        with self.user_lock:
            if transaction.user_id in self.users_in_plan:
                return []

        if not card_profile.is_active:
            if card_profile.expiry_date <= datetime.now():
                print("[ANOMALY] expired_card")
                result = self.anomaly_generators['expired_card'](transaction, card_profile)
            else:
                print("[ANOMALY] not_active_card")
                result = self.anomaly_generators['not_active'](transaction, card_profile)
            return [result]


        if random.random() < 0.05:
            anomaly_types = list(self.anomaly_generators.keys())
            anomaly_types.remove('expired_card')
            anomaly_types.remove('not_active')
            anomaly = random.choice(anomaly_types)
            print(f"[ANOMALY] {anomaly}")

            result = self.anomaly_generators[anomaly](transaction, card_profile)

            if isinstance(result, dict):
                plan_type = result.get("type")

                if plan_type in ["rapid_geo_change", "micro_transactions","duplicate_transactions","round_amounts", "atm_pattern"]:
                    with self.user_lock:
                        if self.active_threads < self.max_threads:
                            self.active_threads += 1
                            plan_thread = threading.Thread(target=self.execute_plan, args=(result,))
                            plan_thread.daemon = True
                            plan_thread.start()

                    return [transaction]

            elif isinstance(result, list):
                return result
            else:
                return [result]

        return [transaction]

    def get_random_card_profile(self) -> CardProfile:
        card_id = random.choice(self.card_ids)
        return self.card_generator.get_card_profile(card_id)
