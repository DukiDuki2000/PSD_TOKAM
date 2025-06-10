from flask import Flask, render_template, jsonify, request
from kafka import KafkaConsumer
import json
import redis
import threading
from datetime import datetime, timedelta
from collections import defaultdict, Counter
import os

app = Flask(__name__)

KAFKA_BROKER = os.getenv('KAFKA_BROKER', 'localhost:9092')
REDIS_HOST = os.getenv('REDIS_HOST', 'localhost')
REDIS_PORT = os.getenv('REDIS_PORT', '6379')
REDIS_DB = os.getenv('REDIS_DB', '0')
REDIS_PASSWORD = os.getenv('REDIS_PASSWORD', 'admin')


class AnomalyMonitor:
    def __init__(self):
        self.redis_client = redis.Redis(
            host=REDIS_HOST,
            port=int(REDIS_PORT),
            db=int(REDIS_DB),
            password=REDIS_PASSWORD,
            decode_responses=True
        )

        self.all_anomalies = []
        self.anomalies_by_card = defaultdict(list)
        self.anomalies_by_user = defaultdict(list)
        self.anomalies_by_type = defaultdict(list)

        self.anomaly_stats = {
            'total_anomalies': 0,
            'anomaly_types': Counter(),
            'severity_distribution': Counter(),
            'hourly_anomalies': defaultdict(int),
            'daily_anomalies': defaultdict(int),
            'top_affected_cards': Counter(),
            'top_affected_users': Counter()
        }

        self.anomaly_consumer_thread = threading.Thread(target=self.consume_anomalies)
        self.anomaly_consumer_thread.daemon = True
        self.anomaly_consumer_thread.start()

    def consume_anomalies(self):
        try:
            consumer = KafkaConsumer(
                'anomaly',
                bootstrap_servers=KAFKA_BROKER,
                value_deserializer=lambda x: json.loads(x.decode('utf-8')),
                auto_offset_reset='earliest',
                group_id='anomaly_consumer',
                enable_auto_commit=False,
            )

            for message in consumer:
                anomaly = message.value
                self.process_anomaly(anomaly)

        except Exception as e:
            print(f"❌ Anomaly consumer error: {e}")

    def process_anomaly(self, anomaly):
        """Przetwarza anomalię"""
        try:
            if 'received_timestamp' not in anomaly:
                anomaly['received_timestamp'] = datetime.now().isoformat()

            self.all_anomalies.append(anomaly)
            card_id = anomaly.get('card_id')
            user_id = anomaly.get('user_id')
            anomaly_type = anomaly.get('anomaly_type')

            if card_id:
                self.anomalies_by_card[card_id].append(anomaly)
            if user_id:
                self.anomalies_by_user[user_id].append(anomaly)
            if anomaly_type:
                self.anomalies_by_type[anomaly_type].append(anomaly)

            self.update_anomaly_stats(anomaly)

            if len(self.all_anomalies) > 5000:
                self.all_anomalies = self.all_anomalies[-2500:]

        except Exception as e:
            print(f"❌ Error processing anomaly: {e}")

    def update_anomaly_stats(self, anomaly):
        """Aktualizuje statystyki anomalii"""
        self.anomaly_stats['total_anomalies'] += 1

        anomaly_type = anomaly.get('anomaly_type', 'unknown')
        severity = anomaly.get('severity', 0)
        card_id = anomaly.get('card_id')
        user_id = anomaly.get('user_id')

        self.anomaly_stats['anomaly_types'][anomaly_type] += 1

        if severity >= 0.9:
            severity_level = 'CRITICAL'
        elif severity >= 0.7:
            severity_level = 'HIGH'
        elif severity >= 0.5:
            severity_level = 'MEDIUM'
        else:
            severity_level = 'LOW'

        self.anomaly_stats['severity_distribution'][severity_level] += 1
        hour = datetime.now().hour
        date = datetime.now().strftime('%Y-%m-%d')
        self.anomaly_stats['hourly_anomalies'][hour] += 1
        self.anomaly_stats['daily_anomalies'][date] += 1
        if card_id:
            self.anomaly_stats['top_affected_cards'][card_id] += 1
        if user_id:
            self.anomaly_stats['top_affected_users'][user_id] += 1

    def get_card_anomalies(self, card_id, limit=None):
        """Pobiera anomalie dla konkretnej karty"""
        anomalies = self.anomalies_by_card.get(card_id, [])
        if limit:
            return anomalies[-limit:]
        return anomalies

    def get_user_anomalies(self, user_id, limit=None):
        """Pobiera anomalie dla konkretnego użytkownika"""
        anomalies = self.anomalies_by_user.get(user_id, [])
        if limit:
            return anomalies[-limit:]
        return anomalies

    def get_anomaly_stats_for_card(self, card_id):
        """Statystyki anomalii dla karty"""
        anomalies = self.anomalies_by_card.get(card_id, [])
        if not anomalies:
            return None

        return {
            'total_anomalies': len(anomalies),
            'anomaly_types': Counter(a.get('anomaly_type', 'unknown') for a in anomalies),
            'avg_severity': sum(a.get('severity', 0) for a in anomalies) / len(anomalies),
            'max_severity': max(a.get('severity', 0) for a in anomalies),
            'recent_anomalies': anomalies[-5:]
        }

    def get_anomaly_stats_for_user(self, user_id):
        """Statystyki anomalii dla użytkownika"""
        anomalies = self.anomalies_by_user.get(user_id, [])
        if not anomalies:
            return None

        affected_cards = set(a.get('card_id') for a in anomalies if a.get('card_id'))

        return {
            'total_anomalies': len(anomalies),
            'affected_cards': list(affected_cards),
            'anomaly_types': Counter(a.get('anomaly_type', 'unknown') for a in anomalies),
            'avg_severity': sum(a.get('severity', 0) for a in anomalies) / len(anomalies),
            'max_severity': max(a.get('severity', 0) for a in anomalies),
            'recent_anomalies': anomalies[-10:]  # 10 najnowszych
        }


class TransactionMonitor:
    def __init__(self):
        self.redis_client = redis.Redis(
            host=REDIS_HOST,
            port=int(REDIS_PORT),
            db=int(REDIS_DB),
            password=REDIS_PASSWORD,
            decode_responses=True
        )

        self.all_transactions = []
        self.transactions_by_card = defaultdict(list)
        self.transactions_by_user = defaultdict(list)
        self.global_stats = {
            'merchant_stats': Counter(),
            'status_stats': Counter(),
            'country_stats': Counter(),
            'hourly_volume': defaultdict(float),
            'daily_volume': defaultdict(float)
        }
        self.consumer_thread = threading.Thread(target=self.consume_transactions)
        self.consumer_thread.daemon = True
        self.consumer_thread.start()

    def consume_transactions(self):
        """Konsumuje transakcje z Kafka"""
        try:
            consumer = KafkaConsumer(
                'transactions',
                bootstrap_servers=KAFKA_BROKER,
                value_deserializer=lambda x: json.loads(x.decode('utf-8')),
                auto_offset_reset='latest'
            )
            for message in consumer:
                transaction = message.value
                self.process_transaction(transaction)

        except Exception as e:
            print(f"❌ Kafka consumer error: {e}")

    def process_transaction(self, transaction):
        """Przetwarza transakcję"""
        try:
            transaction['timestamp'] = datetime.now().isoformat()
            transaction['date'] = datetime.now().strftime('%Y-%m-%d')

            self.all_transactions.append(transaction)
            card_id = transaction.get('card_id')
            user_id = transaction.get('user_id')

            if card_id:
                self.transactions_by_card[card_id].append(transaction)
            if user_id:
                self.transactions_by_user[user_id].append(transaction)

            self.update_global_stats(transaction)

            if len(self.all_transactions) > 10000:
                self.all_transactions = self.all_transactions[-5000:]

        except Exception as e:
            print(f"❌ Error processing transaction: {e}")

    def update_global_stats(self, transaction):
        """Aktualizuje statystyki globalne"""
        location = transaction.get('location', {})
        amount = transaction.get('amount', 0)

        self.global_stats['merchant_stats'][transaction.get('merchant_category', 'unknown')] += 1
        self.global_stats['status_stats'][transaction.get('status', 'approved')] += 1
        self.global_stats['country_stats'][location.get('country', 'Unknown')] += 1

        hour = datetime.now().hour
        date = datetime.now().strftime('%Y-%m-%d')

        self.global_stats['hourly_volume'][hour] += amount
        self.global_stats['daily_volume'][date] += amount

    def get_card_transactions_stats(self, card_id):
        """Statystyki dla konkretnej karty"""
        transactions = self.transactions_by_card.get(card_id, [])
        if not transactions:
            return None

        stats = {
            'total_transactions': len(transactions),
            'total_amount': sum(t.get('amount', 0) for t in transactions),
            'avg_amount': sum(t.get('amount', 0) for t in transactions) / len(transactions),
            'merchant_stats': Counter(t.get('merchant_category', 'unknown') for t in transactions),
            'status_stats': Counter(t.get('status', 'approved') for t in transactions),
            'locations': [t.get('location') for t in transactions if t.get('location')],
            'recent_transactions': transactions[-10:]
        }
        return stats

    def get_user_transactions_stats(self, user_id):
        """Statystyki dla konkretnego użytkownika"""
        transactions = self.transactions_by_user.get(user_id, [])
        if not transactions:
            return None

        cards_used = set(t.get('card_id') for t in transactions)

        stats = {
            'total_transactions': len(transactions),
            'total_amount': sum(t.get('amount', 0) for t in transactions),
            'avg_amount': sum(t.get('amount', 0) for t in transactions) / len(transactions),
            'cards_used': list(cards_used),
            'merchant_stats': Counter(t.get('merchant_category', 'unknown') for t in transactions),
            'status_stats': Counter(t.get('status', 'approved') for t in transactions),
            'countries_visited': Counter(t.get('location', {}).get('country', 'Unknown') for t in transactions),
            'recent_transactions': transactions[-15:]
        }
        return stats



transaction_monitor = TransactionMonitor()
anomaly_monitor = AnomalyMonitor()


# === ROUTES ===

@app.route('/')
def home():
    return render_template('transactions.html')


@app.route('/anomalies')
def anomalies_list():
    return render_template('anomalies.html')


@app.route('/anomaly/<alert_id>')
def anomaly_detail(alert_id):
    return render_template('anomaly_detail.html', alert_id=alert_id)


@app.route('/transaction/<transaction_id>')
def transaction_detail(transaction_id):
    return render_template('transaction_detail.html', transaction_id=transaction_id)


@app.route('/cards')
def cards_list():
    return render_template('cards.html')


@app.route('/card/<card_id>')
def card_detail(card_id):
    return render_template('card_detail.html', card_id=card_id)


@app.route('/users')
def users_list():
    return render_template('users.html')


@app.route('/user/<user_id>')
def user_detail(user_id):
    return render_template('user_detail.html', user_id=user_id)


@app.route('/analytics')
def analytics():
    return render_template('analytics.html')


# === API ENDPOINTS ANOMALII ===

@app.route('/api/anomalies')
def api_anomalies():
    """API: Lista anomalii"""
    page = int(request.args.get('page', 1))
    per_page = int(request.args.get('per_page', 50))
    anomaly_type = request.args.get('type', '').strip()
    severity_filter = request.args.get('severity', '').strip()
    search = request.args.get('search', '').lower().strip()

    filtered_anomalies = anomaly_monitor.all_anomalies[:]

    if anomaly_type:
        filtered_anomalies = [a for a in filtered_anomalies if a.get('anomaly_type') == anomaly_type]

    if severity_filter:
        if severity_filter == 'critical':
            filtered_anomalies = [a for a in filtered_anomalies if a.get('severity', 0) >= 0.9]
        elif severity_filter == 'high':
            filtered_anomalies = [a for a in filtered_anomalies if 0.7 <= a.get('severity', 0) < 0.9]
        elif severity_filter == 'medium':
            filtered_anomalies = [a for a in filtered_anomalies if 0.5 <= a.get('severity', 0) < 0.7]
        elif severity_filter == 'low':
            filtered_anomalies = [a for a in filtered_anomalies if a.get('severity', 0) < 0.5]

    if search:
        filtered_anomalies = [a for a in filtered_anomalies
                              if search in a.get('card_id', '').lower()
                              or search in a.get('user_id', '').lower()
                              or search in a.get('description', '').lower()]

    filtered_anomalies.reverse()
    start = (page - 1) * per_page
    end = start + per_page
    paginated_anomalies = filtered_anomalies[start:end]

    return jsonify({
        'anomalies': paginated_anomalies,
        'total': len(filtered_anomalies),
        'page': page,
        'per_page': per_page,
        'total_pages': (len(filtered_anomalies) + per_page - 1) // per_page if filtered_anomalies else 1
    })


@app.route('/api/anomaly/<alert_id>')
def api_anomaly_detail(alert_id):
    """API: Szczegóły anomalii"""
    anomaly = None
    for a in anomaly_monitor.all_anomalies:
        if a.get('alert_id') == alert_id:
            anomaly = a
            break

    if not anomaly:
        return jsonify({'error': 'Anomaly not found'}), 404

    return jsonify(anomaly)


@app.route('/api/anomaly-stats')
def api_anomaly_stats():
    """API: Statystyki anomalii"""
    stats = anomaly_monitor.anomaly_stats.copy()

    stats['anomaly_types'] = dict(stats['anomaly_types'].most_common(10))
    stats['severity_distribution'] = dict(stats['severity_distribution'])
    stats['hourly_anomalies'] = dict(stats['hourly_anomalies'])
    stats['daily_anomalies'] = dict(stats['daily_anomalies'])
    stats['top_affected_cards'] = dict(stats['top_affected_cards'].most_common(10))
    stats['top_affected_users'] = dict(stats['top_affected_users'].most_common(10))

    return jsonify(stats)


@app.route('/api/card/<card_id>/anomalies')
def api_card_anomalies(card_id):
    """API: Anomalie dla konkretnej karty"""
    anomalies = anomaly_monitor.get_card_anomalies(card_id, limit=50)
    anomaly_stats = anomaly_monitor.get_anomaly_stats_for_card(card_id)

    return jsonify({
        'card_id': card_id,
        'anomalies': anomalies,
        'anomaly_stats': anomaly_stats
    })


@app.route('/api/user/<user_id>/anomalies')
def api_user_anomalies(user_id):
    """API: Anomalie dla konkretnego użytkownika"""
    anomalies = anomaly_monitor.get_user_anomalies(user_id, limit=50)
    anomaly_stats = anomaly_monitor.get_anomaly_stats_for_user(user_id)

    return jsonify({
        'user_id': user_id,
        'anomalies': anomalies,
        'anomaly_stats': anomaly_stats
    })


# === ISTNIEJĄCE API ENDPOINTS TRANSAKCJI ===

@app.route('/api/transactions')
def api_transactions():
    """API: Lista transakcji"""
    page = int(request.args.get('page', 1))
    per_page = int(request.args.get('per_page', 50))

    start = (page - 1) * per_page
    end = start + per_page

    transactions = transaction_monitor.all_transactions[-end:][
                   -per_page:] if transaction_monitor.all_transactions else []
    transactions.reverse()

    return jsonify({
        'transactions': transactions,
        'total': len(transaction_monitor.all_transactions),
        'page': page,
        'per_page': per_page
    })


@app.route('/api/transaction/<transaction_id>')
def api_transaction_detail(transaction_id):
    """API: Szczegóły transakcji"""
    transaction = None
    for t in transaction_monitor.all_transactions:
        if t.get('transaction_id') == transaction_id:
            transaction = t
            break

    if not transaction:
        return jsonify({'error': 'Transaction not found'}), 404

    return jsonify(transaction)


@app.route('/api/cards')
def api_cards():
    """API: Lista kart z Redis z wyszukiwaniem"""
    try:
        page = int(request.args.get('page', 1))
        per_page = int(request.args.get('per_page', 100))
        search = request.args.get('search', '').lower().strip()
        status_filter = request.args.get('status', '').lower().strip()

        cards_count = int(transaction_monitor.redis_client.get("cards:count") or 0)
        cards = []

        if search or status_filter:
            for i in range(cards_count):
                card_id = f"card_{i:06d}"
                profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")

                if profile_data:
                    profile = json.loads(profile_data)
                    tx_count = len(transaction_monitor.transactions_by_card.get(card_id, []))
                    profile['transaction_count'] = tx_count

                    if status_filter:
                        if status_filter == 'active' and not profile.get('is_active', False):
                            continue
                        if status_filter == 'inactive' and profile.get('is_active', False):
                            continue
                    if search:
                        searchable_text = f"{profile.get('card_id', '')} {profile.get('user_id', '')}".lower()
                        if search not in searchable_text:
                            continue

                    cards.append(profile)
            total_filtered = len(cards)
            start_idx = (page - 1) * per_page
            end_idx = start_idx + per_page
            cards = cards[start_idx:end_idx]

            return jsonify({
                'cards': cards,
                'total': total_filtered,
                'page': page,
                'per_page': per_page,
                'total_pages': (total_filtered + per_page - 1) // per_page if total_filtered > 0 else 1,
                'filtered': True
            })

        else:
            start_idx = (page - 1) * per_page
            end_idx = min(start_idx + per_page, cards_count)

            for i in range(start_idx, end_idx):
                card_id = f"card_{i:06d}"
                profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")

                if profile_data:
                    profile = json.loads(profile_data)
                    tx_count = len(transaction_monitor.transactions_by_card.get(card_id, []))
                    profile['transaction_count'] = tx_count
                    cards.append(profile)

            return jsonify({
                'cards': cards,
                'total': cards_count,
                'page': page,
                'per_page': per_page,
                'total_pages': (cards_count + per_page - 1) // per_page,
                'filtered': False
            })

    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/card/<card_id>')
def api_card_detail(card_id):
    """API: Szczegóły karty Z ANOMALIAMI"""
    try:
        profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")
        if not profile_data:
            return jsonify({'error': 'Card not found'}), 404

        profile = json.loads(profile_data)
        tx_stats = transaction_monitor.get_card_transactions_stats(card_id)
        card_anomalies = anomaly_monitor.get_card_anomalies(card_id, limit=20)
        anomaly_stats = anomaly_monitor.get_anomaly_stats_for_card(card_id)

        return jsonify({
            'profile': profile,
            'transaction_stats': tx_stats,
            'anomalies': card_anomalies,
            'anomaly_stats': anomaly_stats
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/users')
def api_users():
    """API: Lista użytkowników z wyszukiwaniem"""
    try:
        page = int(request.args.get('page', 1))
        per_page = int(request.args.get('per_page', 100))
        search = request.args.get('search', '').lower().strip()
        activity_filter = request.args.get('activity', '').lower().strip()
        user_keys = transaction_monitor.redis_client.keys("user_cards:*")
        users = []
        for key in user_keys:
            user_id = key.replace("user_cards:", "")
            cards_data = transaction_monitor.redis_client.get(key)

            if cards_data:
                user_cards = json.loads(cards_data)
                tx_count = len(transaction_monitor.transactions_by_user.get(user_id, []))
                anomaly_count = len(anomaly_monitor.anomalies_by_user.get(user_id, []))

                user_data = {
                    'user_id': user_id,
                    'cards_count': len(user_cards),
                    'transaction_count': tx_count,
                    'anomaly_count': anomaly_count,
                    'cards': user_cards
                }
                if activity_filter:
                    if activity_filter == 'active' and tx_count == 0:
                        continue
                    if activity_filter == 'inactive' and tx_count > 0:
                        continue
                if search:
                    searchable_text = f"{user_id} {' '.join(user_cards)}".lower()
                    if search not in searchable_text:
                        continue

                users.append(user_data)
        total_filtered = len(users)
        start_idx = (page - 1) * per_page
        end_idx = start_idx + per_page
        paginated_users = users[start_idx:end_idx]

        return jsonify({
            'users': paginated_users,
            'total': total_filtered,
            'page': page,
            'per_page': per_page,
            'total_pages': (total_filtered + per_page - 1) // per_page if total_filtered > 0 else 1,
            'filtered': bool(search or activity_filter)
        })

    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/user/<user_id>')
def api_user_detail(user_id):
    """API: Szczegóły użytkownika Z ANOMALIAMI"""
    try:
        cards_data = transaction_monitor.redis_client.get(f"user_cards:{user_id}")
        if not cards_data:
            return jsonify({'error': 'User not found'}), 404

        user_cards = json.loads(cards_data)
        tx_stats = transaction_monitor.get_user_transactions_stats(user_id)
        cards_profiles = []
        for card_id in user_cards:
            profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")
            if profile_data:
                cards_profiles.append(json.loads(profile_data))
        user_anomalies = anomaly_monitor.get_user_anomalies(user_id, limit=30)
        anomaly_stats = anomaly_monitor.get_anomaly_stats_for_user(user_id)

        return jsonify({
            'user_id': user_id,
            'cards': cards_profiles,
            'transaction_stats': tx_stats,
            'anomalies': user_anomalies,
            'anomaly_stats': anomaly_stats
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/redis-data')
def api_redis_data():
    """API: Dane z Redis"""
    try:
        cards_count = int(transaction_monitor.redis_client.get("cards:count") or 0)
        user_keys_count = len(transaction_monitor.redis_client.keys("user_cards:*"))
        active_cards = 0
        inactive_cards = 0
        total_balance = 0
        sample_size = min(1000, cards_count)
        for i in range(0, sample_size):
            card_id = f"card_{i:06d}"
            profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")
            if profile_data:
                profile = json.loads(profile_data)
                if profile.get('is_active', False):
                    active_cards += 1
                else:
                    inactive_cards += 1
                total_balance += profile.get('current_balance', 0)
        if sample_size > 0:
            ratio = cards_count / sample_size
            active_cards = int(active_cards * ratio)
            inactive_cards = int(inactive_cards * ratio)
            total_balance = total_balance * ratio

        avg_balance = total_balance / cards_count if cards_count > 0 else 0

        return jsonify({
            'cards_count': cards_count,
            'users_count': user_keys_count,
            'active_cards': active_cards,
            'inactive_cards': inactive_cards,
            'total_balance': total_balance,
            'avg_balance': avg_balance
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/analytics')
def api_analytics():
    """API: Dane analityczne Z ANOMALIAMI"""
    cards_count = int(transaction_monitor.redis_client.get("cards:count") or 0)
    user_keys_count = len(transaction_monitor.redis_client.keys("user_cards:*"))
    anomaly_stats = anomaly_monitor.anomaly_stats.copy()
    anomaly_types_dict = dict(anomaly_stats['anomaly_types'].most_common(10))
    severity_dist_dict = dict(anomaly_stats['severity_distribution'])

    return jsonify({
        'global_stats': {
            'merchant_stats': dict(transaction_monitor.global_stats['merchant_stats'].most_common(10)),
            'status_stats': dict(transaction_monitor.global_stats['status_stats']),
            'country_stats': dict(transaction_monitor.global_stats['country_stats'].most_common(10)),
            'hourly_volume': dict(transaction_monitor.global_stats['hourly_volume']),
            'daily_volume': dict(transaction_monitor.global_stats['daily_volume']),
            'anomaly_types': anomaly_types_dict  # POPRAWIONE
        },
        'total_transactions': len(transaction_monitor.all_transactions),
        'active_cards': len(transaction_monitor.transactions_by_card),
        'active_users': len(transaction_monitor.transactions_by_user),
        'redis_stats': {
            'total_cards_in_redis': cards_count,
            'total_users_in_redis': user_keys_count
        },
        'anomaly_stats': {
            'total_anomalies': anomaly_stats['total_anomalies'],
            'anomaly_types': anomaly_types_dict,
            'severity_distribution': severity_dist_dict,
            'top_affected_cards': dict(anomaly_stats['top_affected_cards'].most_common(10)),
            'top_affected_users': dict(anomaly_stats['top_affected_users'].most_common(10))
        }
    })


@app.route('/api/locations')
def api_locations():
    """API: Lokalizacje dla map"""
    locations = []
    for transaction in transaction_monitor.all_transactions[-200:]:
        location = transaction.get('location')
        if location and location.get('latitude') and location.get('longitude'):
            locations.append({
                'lat': location.get('latitude'),
                'lng': location.get('longitude'),
                'city': location.get('city'),
                'country': location.get('country'),
                'amount': transaction.get('amount', 0),
                'status': transaction.get('status', 'approved'),
                'transaction_id': transaction.get('transaction_id'),
                'merchant_category': transaction.get('merchant_category', 'general')
            })

    return jsonify(locations)


@app.route('/api/stats')
def api_stats():
    """API: Podstawowe statystyki dla dashboard Z ANOMALIAMI"""
    return jsonify({
        'total_transactions': len(transaction_monitor.all_transactions),
        'total_anomalies': len(anomaly_monitor.all_anomalies),  # DODANE
        'merchant_stats': dict(transaction_monitor.global_stats['merchant_stats'].most_common(10)),
        'status_stats': dict(transaction_monitor.global_stats['status_stats']),
        'country_stats': dict(transaction_monitor.global_stats['country_stats'].most_common(10)),
        'hourly_volume': dict(transaction_monitor.global_stats['hourly_volume']),
        'anomaly_types': dict(anomaly_monitor.anomaly_stats['anomaly_types'].most_common(5)),  # DODANE
        'amount_ranges': {
            '0-50': len([t for t in transaction_monitor.all_transactions if t.get('amount', 0) <= 50]),
            '50-200': len([t for t in transaction_monitor.all_transactions if 50 < t.get('amount', 0) <= 200]),
            '200-500': len([t for t in transaction_monitor.all_transactions if 200 < t.get('amount', 0) <= 500]),
            '500-1000': len([t for t in transaction_monitor.all_transactions if 500 < t.get('amount', 0) <= 1000]),
            '1000+': len([t for t in transaction_monitor.all_transactions if t.get('amount', 0) > 1000])
        }
    })


@app.route('/api/card-profiles')
def api_card_profiles():
    """API: Profile kart dla dashboard"""
    try:
        profiles = []
        cards_count = int(transaction_monitor.redis_client.get("cards:count") or 0)
        for i in range(min(50, cards_count)):
            card_id = f"card_{i:06d}"
            profile_data = transaction_monitor.redis_client.get(f"card_profile:{card_id}")
            if profile_data:
                profile = json.loads(profile_data)
                profiles.append(profile)

        return jsonify(profiles)
    except Exception as e:
        return jsonify([])



@app.errorhandler(404)
def not_found(error):
    return jsonify({'error': 'Not found'}), 404



@app.errorhandler(500)
def internal_error(error):
    return jsonify({'error': 'Internal server error'}), 500


if __name__ == '__main__':
    print("Dashboard available at: http://localhost:5000")
    app.run(debug=True, host='0.0.0.0', port=5000)