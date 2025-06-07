from dataclasses import dataclass

@dataclass
class Transaction:
    transaction_id: str
    card_id: str
    user_id: str
    amount: float
