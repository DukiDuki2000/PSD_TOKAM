import random
import uuid

from python.generator.transaction import Transaction
from python.generator.card_profile import CardProfile
from python.generator.location_utils import LocationUtils
from datetime import datetime, timedelta

class AnomalyGenerator:

    @staticmethod
    def amount_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia dużych kwot - transakcja znacznie przekraczająca średnią"""
        multiplier = random.uniform(5, 20)
        transaction.amount = card_profile.avg_amount * multiplier
        return transaction

    @staticmethod
    def insufficient_funds_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia przekroczenia limitu karty - za mało środków"""
        transaction.amount = card_profile.current_balance + random.uniform(100, 1000)
        transaction.status = "declined"
        return transaction

    @staticmethod
    def expired_card_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia starej karty kredytowej (przeterminowana karta)"""
        card_profile.expiry_date = datetime.now() - timedelta(days=random.randint(1, 365))
        transaction.status = "declined"
        return transaction

    @staticmethod
    def not_active_card_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia karty nieaktywnej"""
        card_profile.is_active = False
        transaction.status = "declined"
        return transaction

    @staticmethod
    def transaction_limit_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia przekroczenia limitu karty - transakcja powyżej 1000 zł"""
        transaction.amount = round(card_profile.transaction_limit + random.uniform(100, 2000), 2)
        transaction.status = "declined"
        return transaction

    @staticmethod
    def micro_transaction_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia dużej ilości groszowych transakcji - plan czasowy"""
        num_transactions = random.randint(3, 10)

        micro_plan = {
            "type": "micro_transactions",
            "user_id": card_profile.user_id,
            "card_id": card_profile.card_id,
            "location": transaction.location,
            "count": num_transactions,
            "intervals_seconds": [random.randint(2, 8) for _ in range(num_transactions)],
            "amounts": [round(random.uniform(0.01, 0.99), 2) for _ in range(num_transactions)]
        }
        return micro_plan

    @staticmethod
    def rapid_geo_change_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia szybkich zmian geograficznych"""
        foreign_locations = [
            LocationUtils.get_random_foreign_city(),
            LocationUtils.get_random_foreign_city(),
        ]

        geo_plan = {
            "type": "rapid_geo_change",
            "user_id": card_profile.user_id,
            "card_id": card_profile.card_id,
            "locations": foreign_locations,
            "intervals_seconds": [random.randint(5, 10), random.randint(5, 10)],
            "amounts": [round(random.uniform(100, 800), 2) for _ in foreign_locations]
        }
        return geo_plan

    @staticmethod
    def duplicate_transaction_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia duplikatów - ta sama transakcja powtórzona kilka razy w krótkim czasie"""
        num_duplicates = random.randint(2, 5)  # 2-5 duplikatów
        duplicate_plan = {
            "type": "duplicate_transactions",
            "user_id": card_profile.user_id,
            "card_id": card_profile.card_id,
            "location": transaction.location,
            "count": num_duplicates,
            "intervals_seconds": [random.randint(3, 15) for _ in range(num_duplicates)],
            "amounts": [transaction.amount] * num_duplicates,
            "original_amount": transaction.amount
        }
        return duplicate_plan




