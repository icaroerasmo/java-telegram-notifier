package com.icaroerasmo.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

/**
 * Searchable log content (one doc per DOCUMENT notification). Lives in the "logs"
 * index with a 10-day TTL keyed on {@code contentStoredAt}. Denormalized display
 * fields avoid a join against the "notifications" index on the search path.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "logs", createIndex = false)
@Setting(shards = 1, replicas = 0)
public class LogDocument {

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Text)
    private String logContent;

    @Field(type = FieldType.Keyword)
    private String filename;

    @Field(type = FieldType.Text)
    private String summary;

    @Field(type = FieldType.Keyword)
    private String sender;

    @Field(type = FieldType.Keyword)
    private String kind;

    @Field(type = FieldType.Keyword)
    private String mediaType;

    @Field(type = FieldType.Keyword)
    private String fileId;

    @Field(type = FieldType.Keyword)
    private String sentAt;

    @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
    private long timestamp;

    @Field(type = FieldType.Keyword)
    private String date;

    @Field(type = FieldType.Keyword)
    private String hour;

    @Field(type = FieldType.Long)
    private long size;

    /** Set to "now" at index/reload time; the TTL delete is based on this, not timestamp. */
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
    private long contentStoredAt;
}
