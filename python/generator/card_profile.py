import random

class CardProfile:
    def __init__(self, card_id: str, user_id: str):
        self.card_id = card_id
        self.user_id = user_id
        self.avg_amount = random.uniform(50, 500)
        self.std_amount = self.avg_amount * 0.3
        self.daily_limit = random.uniform(1000, 5000)
        self.monthly_limit = random.uniform(10000, 50000)
        # self.is_active = True