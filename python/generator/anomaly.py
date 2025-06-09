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
        transaction.amount = round(card_profile.avg_amount * multiplier,2)
        return transaction

    @staticmethod
    def insufficient_funds_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia przekroczenia limitu karty - za mało środków"""
        transaction.amount = round(card_profile.current_balance + random.uniform(100, 1000),2)
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
        """Anomalia przekroczenia limitu karty"""
        transaction.amount = round(card_profile.transaction_limit + random.uniform(100, 2000), 2)
        transaction.status = "declined"
        return transaction

    @staticmethod
    def micro_transaction_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia dużej ilości groszowych transakcji """
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
        """Anomalia duplikatów"""
        num_duplicates = random.randint(2, 5)
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

    @staticmethod
    def round_amount_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia okrągłych kwot"""
        num_transactions = random.randint(3, 7)
        round_amounts = []
        for _ in range(num_transactions):
            round_type = random.choice(['tens', 'fifties', 'hundreds'])
            if round_type == 'tens':
                amount = random.randint(1, 9) * 10.00
            elif round_type == 'fifties':
                amount = random.randint(1, 10) * 50.00
            else:
                amount = random.randint(1, 10) * 100.00

            round_amounts.append(amount)

        round_plan = {
            "type": "round_amounts",
            "user_id": card_profile.user_id,
            "card_id": card_profile.card_id,
            "location": transaction.location,
            "count": num_transactions,
            "intervals_seconds": [random.randint(10, 30) for _ in range(num_transactions)],
            "amounts": round_amounts
        }
        return round_plan

    @staticmethod
    def atm_pattern_anomaly(transaction: Transaction, card_profile: CardProfile) -> dict:
        """Anomalia wzorca bankomatowego"""
        atm_amounts = [20.00, 50.00, 100.00, 200.00, 300.00, 500.00]
        num_attempts = random.randint(3, 6)
        available_amounts = [amt for amt in atm_amounts if amt <= card_profile.transaction_limit]
        if not available_amounts:
            available_amounts = [20.00, 50.00]
        atm_plan = {
            "type": "atm_pattern",
            "user_id": card_profile.user_id,
            "card_id": card_profile.card_id,
            "location": transaction.location,
            "count": num_attempts,
            "intervals_seconds": [random.randint(5, 20) for _ in range(num_attempts)],
            "amounts": [random.choice(available_amounts) for _ in range(num_attempts)]
        }
        return atm_plan

    @staticmethod
    def unusual_merchant_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        """Anomalia nietypowego sprzedawcy """
        suspicious_categories = ["gambling", "luxury_goods", "crypto_exchange", "adult_services", "high_risk_merchant"]
        category = random.choice(suspicious_categories)

        transaction.amount = round(transaction.amount * random.uniform(3, 8), 2)
        transaction.merchant_category = category
        return transaction




