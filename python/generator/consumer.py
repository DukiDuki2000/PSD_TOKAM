from flask import Flask, Response, render_template
from kafka import KafkaConsumer
import json
import os
app = Flask(__name__)


KAFKA_BROKER = os.getenv('KAFKA_BROKER', 'localhost:9092')
TOPIC = os.getenv('KAFKA_TOPIC', 'sensor_data')
FLASK_HOST = os.getenv('FLASK_HOST', '0.0.0.0')
FLASK_PORT = int(os.getenv('FLASK_PORT', '8080'))
FLASK_DEBUG = os.getenv('FLASK_DEBUG', 'True').lower() == 'true'

def stream_kafka():
    consumer = KafkaConsumer(
        TOPIC,
        bootstrap_servers=KAFKA_BROKER,
        value_deserializer=lambda x: json.loads(x.decode('utf-8')),
        auto_offset_reset='latest',
    )

    for message in consumer:
        yield f"data: {json.dumps(message.value)}\n\n"

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/stream')
def stream():
    return Response(stream_kafka(), mimetype='text/event-stream')


if __name__ == '__main__':
    app.run(debug=FLASK_DEBUG, threaded=True, port=FLASK_PORT, host=FLASK_HOST)