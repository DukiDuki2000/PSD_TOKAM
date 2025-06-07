import random
import time
import os
from python.generator.generator import TransactionGenerator
from python.generator.card_generator import CardGenerator

REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = os.getenv('REDIS_PORT', '6379')
REDIS_DB = os.getenv('REDIS_DB', '0')
REDIS_PASSWORD= os.getenv('REDIS_PASSWORD', 'admin')


class Simulate:
    def __init__(self,transaction_generator: TransactionGenerator):
        self.transaction_generator = transaction_generator

    def simulate_transaction(self, duration: int = 3600, transaction_per_second: int = 5):
        start_time = time.time()
        interval = 1.0
        while time.time() - start_time < duration:
            batch_start = time.time()
            for _ in range(transaction_per_second):
                card_profile = self.transaction_generator.get_random_card_profile()
                transaction = self.transaction_generator.generate_transaction(card_profile)
                transaction = self.transaction_generator.inject_anomaly(transaction, card_profile)
                self.transaction_generator.send_to_kafka(transaction)

            batch_duration = time.time() - batch_start
            sleep_time = max(0, interval - batch_duration)

            if sleep_time > 0:
                time.sleep(sleep_time)


def generate_cards(num_cards: int = 10000, num_users: int = 2000):
    print(f"Generowanie {num_cards} kart dla {num_users} użytkowników...")
    card_gen = CardGenerator(REDIS_HOST, REDIS_PORT, REDIS_DB, REDIS_PASSWORD)
    cards_per_user = card_gen.generate(num_cards, num_users)
    print(f"Karty wygenerowane pomyślnie!")
    return cards_per_user


if __name__ == '__main__':
    generate_cards(10000, 8000)
    transaction_generator = TransactionGenerator(10000,8000)
    simulator = Simulate(transaction_generator)
    simulator.simulate_transaction(duration=3600, transaction_per_second=200)