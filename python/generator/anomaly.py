import random
from python.generator.transaction import Transaction
from python.generator.card_profile import CardProfile


class AnomalyGenerator:

    @staticmethod
    def amount_anomaly(transaction: Transaction, card_profile: CardProfile) -> Transaction:
        multiplier = random.uniform(5, 20)
        transaction.amount = card_profile.avg_amount * multiplier
        return transaction