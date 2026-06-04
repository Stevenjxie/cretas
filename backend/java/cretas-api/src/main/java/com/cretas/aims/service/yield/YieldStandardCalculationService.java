package com.cretas.aims.service.yield;

public interface YieldStandardCalculationService {

    Result recalculateFactory(String factoryId);

    class Result {
        private int processed;
        private int updated;
        private int skippedInsufficientData;
        private int skippedManual;
        private int failed;

        public int getProcessed() {
            return processed;
        }

        public int getUpdated() {
            return updated;
        }

        public int getSkippedInsufficientData() {
            return skippedInsufficientData;
        }

        public int getSkippedManual() {
            return skippedManual;
        }

        public int getFailed() {
            return failed;
        }

        public void incrementProcessed() {
            processed++;
        }

        public void incrementUpdated() {
            updated++;
        }

        public void incrementSkippedInsufficientData() {
            skippedInsufficientData++;
        }

        public void incrementSkippedManual() {
            skippedManual++;
        }

        public void incrementFailed() {
            failed++;
        }
    }
}
