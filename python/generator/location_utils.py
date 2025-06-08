from python.generator.location import Location
import random
class LocationUtils:
    POLISH_CITIES = [
        Location(52.2297, 21.0122, "Warsaw", "Poland", "PL"),
        Location(50.0647, 19.9450, "Krakow", "Poland", "PL"),
        Location(51.1079, 17.0385, "Wroclaw", "Poland", "PL"),
        Location(54.3520, 18.6466, "Gdansk", "Poland", "PL"),
        Location(51.7592, 19.4560, "Lodz", "Poland", "PL"),
        Location(53.4285, 14.5528, "Szczecin", "Poland", "PL"),
        Location(50.2649, 19.0238, "Katowice", "Poland", "PL"),
        Location(51.2465, 22.5684, "Lublin", "Poland", "PL"),
        Location(53.1325, 23.1688, "Bialystok", "Poland", "PL"),
        Location(50.0413, 21.9991, "Rzeszow", "Poland", "PL"),
    ]

    FOREIGN_CITIES = [
        Location(40.7128, -74.0060, "New York", "USA", "US"),
        Location(51.5074, -0.1278, "London", "United Kingdom", "GB"),
        Location(48.8566, 2.3522, "Paris", "France", "FR"),
        Location(52.5200, 13.4050, "Berlin", "Germany", "DE"),
        Location(55.7558, 37.6176, "Moscow", "Russia", "RU"),
        Location(35.6762, 139.6503, "Tokyo", "Japan", "JP"),
        Location(39.9042, 116.4074, "Beijing", "China", "CN"),
        Location(-33.8688, 151.2093, "Sydney", "Australia", "AU"),
        Location(25.2048, 55.2708, "Dubai", "UAE", "AE"),
        Location(1.3521, 103.8198, "Singapore", "Singapore", "SG"),
        Location(-23.5505, -46.6333, "Sao Paulo", "Brazil", "BR"),
        Location(19.4326, -99.1332, "Mexico City", "Mexico", "MX"),
    ]

    @staticmethod
    def get_random_polish_city() -> Location:
        """Zwraca losowe polskie miasto"""
        return random.choice(LocationUtils.POLISH_CITIES)

    @staticmethod
    def get_random_foreign_city() -> Location:
        """Zwraca losowe zagraniczne miasto"""
        return random.choice(LocationUtils.FOREIGN_CITIES)

