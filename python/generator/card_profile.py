import random
from datetime import datetime, timedelta


class CardProfile:
    def __init__(self, card_id: str, user_id: str):
        self.card_id = card_id
        self.user_id = user_id
        self.avg_amount = round(random.uniform(50, 500), 2)
        self.std_amount = round(self.avg_amount * 0.3, 2)
        self.transaction_limit = round(random.uniform(10, 40), 0) * 100

        self.daily_limit = round(random.uniform(self.transaction_limit * 1.5, self.transaction_limit * 5.0), 2)
        self.monthly_limit = round(random.uniform(self.daily_limit * 10, self.daily_limit * 30), 2)

        self.expiry_date = self._generate_expiry_date()
        self.is_active = self._generate_active_status()

        self.current_balance = round(random.uniform(1000, 10000), 2)

    def _generate_expiry_date(self) -> datetime:
        if random.random() < 0.01:
            return datetime.now() - timedelta(days=random.randint(1, 365))
        else:
            return datetime.now() + timedelta(days=random.randint(365, 1825))

    def _generate_active_status(self) -> bool:
        if self.expiry_date <= datetime.now():
            return False
        return random.random() < 0.97