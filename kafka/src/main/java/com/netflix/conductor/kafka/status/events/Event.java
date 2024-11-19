/*
 * Copyright 2024 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.kafka.status.events;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Event<T> {
    private final String eventId;
    private final T payload;
    private OffsetDateTime eventTime;
    private String eventType;
    private String correlationId;
    private String domain;
    private String title;
    private String description;
    private String priority;
    private OffsetDateTime timeOccurred;

    public Event(T payload) {
        this.eventId = UUID.randomUUID().toString();
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public T getPayload() {
        return payload;
    }

    public OffsetDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(OffsetDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public OffsetDateTime getTimeOccurred() {
        return timeOccurred;
    }

    public void setTimeOccurred(OffsetDateTime timeOccurred) {
        this.timeOccurred = timeOccurred;
    }
}
