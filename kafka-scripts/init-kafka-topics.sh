#!/bin/bash

echo "=== Kafka Topics Initialization ==="

wait_for_kafka() {
    echo "Waiting for Kafka to start..."
    while ! kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
        echo "Kafka is not ready yet. Waiting 5 seconds..."
        sleep 5
    done
    echo "✓ Kafka is ready!"
}

create_topic() {
    local topic_name=$1
    local partitions=$2
    local replication_factor=$3

    echo "Creating topic: $topic_name (partitions: $partitions, replication: $replication_factor)"

    kafka-topics --create \
        --if-not-exists \
        --bootstrap-server localhost:9092 \
        --partitions $partitions \
        --replication-factor $replication_factor \
        --topic $topic_name

    if [ $? -eq 0 ]; then
        echo "✓ Topic '$topic_name' created successfully"
    else
        echo "✗ Error while creating topic '$topic_name'"
    fi
}

wait_for_kafka

echo ""
echo "=== Creating Topics ==="

# Skladnia: create_topic "topic-name" number_of_partitions replication_factor

create_topic "transakcje" 1 1

echo ""
echo "=== Summary of Created Topics ==="
kafka-topics --bootstrap-server localhost:9092 --list

echo ""
echo "=== Initialization Completed Successfully! ==="
