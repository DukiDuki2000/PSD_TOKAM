import redis
import json
import random

from typing import List

from python.generator.card_profile import CardProfile
from datetime import datetime

class CardGenerator:
    def __init__(self, redis_host: str, redis_port: str, redis_db: str, redis_password: str):
        self.redis_client = redis.Redis(host=redis_host,port=int(redis_port),db=int(redis_db),password=redis_password,decode_responses=True)

    def generate(self, num_cards: int, num_users: int = 2000):
        user_ids = [f"user_{i}" for i in range(num_users)]
        cards_per_user = {}
        for user_id in user_ids:
            cards_per_user[user_id] = []

        for i in range(num_cards):
            card_id = f"card_{i:06d}"
            if i < num_users:
                user_id = user_ids[i]
            else:
                user_id = random.choice(user_ids)

            cards_per_user[user_id].append(card_id)

            card_profile = CardProfile(card_id, user_id)

            profile_json = {
                "card_id": card_profile.card_id,
                "user_id": card_profile.user_id,
                "avg_amount": card_profile.avg_amount,
                "std_amount": card_profile.std_amount,
                "daily_limit": card_profile.daily_limit,
                "monthly_limit": card_profile.monthly_limit,
                "transaction_limit": card_profile.transaction_limit,
                "is_active": card_profile.is_active,
                "current_balance": card_profile.current_balance,
                "expiry_date": card_profile.expiry_date.isoformat()
            }
            self.redis_client.set(f"card_profile:{card_id}", json.dumps(profile_json))

        for user_id, card_list in cards_per_user.items():
            self.redis_client.set(f"user_cards:{user_id}", json.dumps(card_list))
        self.redis_client.set("cards:count", num_cards)

        return cards_per_user

    def get_card_profile(self, card_id: str) -> CardProfile:
        profile_data = self.redis_client.get(f"card_profile:{card_id}")
        if not profile_data:
            raise ValueError(f"Card profile not found: {card_id}")

        data = json.loads(profile_data)

        card_profile = CardProfile.__new__(CardProfile)
        card_profile.card_id = data["card_id"]
        card_profile.user_id = data["user_id"]
        card_profile.avg_amount = data["avg_amount"]
        card_profile.std_amount = data["std_amount"]
        card_profile.daily_limit = data["daily_limit"]
        card_profile.monthly_limit = data["monthly_limit"]
        card_profile.transaction_limit = data["transaction_limit"]
        card_profile.is_active = data["is_active"]
        card_profile.current_balance = data["current_balance"]
        card_profile.expiry_date = datetime.fromisoformat(data["expiry_date"])

        return card_profile

    def get_all_card_ids(self) -> List[str]:
        card_count = self.redis_client.get("cards:count")
        if not card_count:
            return []

        if isinstance(card_count, bytes):
            card_count = card_count.decode()

        return [f"card_{i:06d}" for i in range(int(card_count))]
