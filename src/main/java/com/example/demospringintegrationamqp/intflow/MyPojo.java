package com.example.demospringintegrationamqp.intflow;

public class MyPojo {
        String event;
        int retryCount;

        public MyPojo() {
            this.event = "";
            this.retryCount = 0;
        }

        public MyPojo(String event) {
            this.event = event;
            this.retryCount = 0;
        }

        public MyPojo(String event, int retryCount) {
            this.event = event;
            this.retryCount = retryCount;
        }

    public int getRetryCount() {
        return retryCount;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
