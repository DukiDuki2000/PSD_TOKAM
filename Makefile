FLINK_JOB_CMD := ./bin/flink run /opt/flink/jobs/anomalydetector/target/anomalydetector-1.0-SNAPSHOT.jar
CONTAINER_NAME := flink-jobmanager
.PHONY: run-job help
run-job:
	@echo "Uruchamianie Flink job w kontenerze $(CONTAINER_NAME) przez bash..."
	docker exec -it $(CONTAINER_NAME) bash -c "./bin/flink run /opt/flink/jobs/anomalydetector/target/anomalydetector-1.0-SNAPSHOT.jar"
list-jobs:
	@echo "Lista uruchomionych jobów:"
	docker exec $(CONTAINER_NAME) ./bin/flink list
