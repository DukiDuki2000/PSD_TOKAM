from dataclasses import dataclass

@dataclass
class Location:
    latitude: float
    longitude: float
    city: str
    country: str
    country_code: str