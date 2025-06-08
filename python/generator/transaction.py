from dataclasses import dataclass
from python.generator.location import Location
@dataclass
class Transaction:
    transaction_id: str
    card_id: str
    user_id: str
    amount: float
    location: Location
    status: str = "approved"

