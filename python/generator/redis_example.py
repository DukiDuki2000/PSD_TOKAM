import redis
import json
import time
from datetime import datetime
r = redis.Redis(host='localhost', port=6379, db=0, password='admin')
r.set("mykey", "hello from Windows")
print(r.get("mykey"))
r.setex("mykey2", 10, "mordo")
r.set("mykey", "hello from linux")
r.setnx("mykey3", "hello from siema")


def create_card_data(card_number, location):
    card_data = {
        "card_number": card_number,
        "timestamp": datetime.now().isoformat(),
        "location": location
    }
    return json.dumps(card_data, ensure_ascii=False)


cards_data = [
    ("1234-5678-9012-3456", "Warszawa, ul. Tokarska"),
    ("1234-5678-9012-3456", "Warszawa, ul. Zychowa"),
    ("9876-5432-1098-7654", "Warszawa, ul. Trzcinska"),

]

for i, (card_num, location,) in enumerate(cards_data):
    key = f"card:{card_num}:{int(time.time())}:{i}"
    card_json = create_card_data(card_num, location)

    r.setex(key, 60, card_json)

    print(f"Dodano: {key}")
    time.sleep(0.1)
