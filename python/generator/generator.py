import random
import json
import os
import numpy as np
import uuid

from kafka import KafkaProducer

from python.generator.transaction import Transaction
from python.generator.card_profile import CardProfile
from python.generator.card_generator import CardGenerator
from python.generator.anomaly import AnomalyGenerator

KAFKA_BROKER = os.getenv('KAFKA_BROKER', 'localhost:9092')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = os.getenv('REDIS_PORT', '6379')
REDIS_DB = os.getenv('REDIS_DB', '0')
REDIS_PASSWORD = os.getenv('REDIS_PASSWORD', 'admin')


class TransactionGenerator:
    def __init__(self, num_cards: int = 10000, num_users: int = 2000):
        self.kafka_producer = KafkaProducer(
            bootstrap_servers=KAFKA_BROKER,
            value_serializer=lambda x: x.encode('utf-8'),
            key_serializer=lambda x: x.encode('utf-8') if x else None
        )

        self.num_cards = num_cards
        self.num_users = num_users

        self.anomaly_generators = {'amount': AnomalyGenerator.amount_anomaly,}

        self.card_generator = CardGenerator(REDIS_HOST, REDIS_PORT, REDIS_DB, REDIS_PASSWORD)
        self.card_ids = self.card_generator.get_all_card_ids()

    def send_to_kafka(self, transaction: Transaction):
        try:
            transaction_json = json.dumps({
                "transaction_id": transaction.transaction_id,
                "card_id": transaction.card_id,
                "user_id": transaction.user_id,
                "amount": transaction.amount,
            })

            self.kafka_producer.send(
                topic='transactions',
                key=transaction.card_id,
                value=transaction_json
            )

        except Exception as e:
            self.logger.error(f"Failed to send transaction to Kafka: {e}")

    def generate_transaction(self, card_profile: CardProfile) -> Transaction:
        amount = round(max(5.0, np.random.normal(card_profile.avg_amount, card_profile.std_amount)), 2)
        amount = min(amount, card_profile.daily_limit * 0.1)

        transaction = Transaction(
            transaction_id=f"tx_{uuid.uuid4().hex[:12]}",
            card_id=card_profile.card_id,
            user_id=card_profile.user_id,
            amount=amount
        )

        return transaction

    def inject_anomaly(self, transaction: Transaction, card_profile: CardProfile) -> Transaction:
        if random.random() < 0.05:
            anomaly_types = list(self.anomaly_generators.keys())
            anomaly = random.choice(anomaly_types)
            transaction = self.anomaly_generators[anomaly](transaction, card_profile)

        return transaction

    def get_random_card_profile(self) -> CardProfile:
        card_id = random.choice(self.card_ids)
        return self.card_generator.get_card_profile(card_id)
