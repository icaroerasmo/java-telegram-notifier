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
 * Durable metadata document for every notification (TEXT/PHOTO/ANIMATION/DOCUMENT).
 * Lives in the "notifications" index (no TTL). Media content is NOT stored here —
 * only the Telegram fileId (or a future storageRef when Telegram is dropped).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "notifications", createIndex = false)
@Setting(shards = 1, replicas = 0)
public class NotificationDocument {

    @Id
    @Field(type = FieldType.Keyword)
    private String id;

    @Field(type = FieldType.Keyword)
    private String sender;

    @Field(type = FieldType.Keyword)
    private String mediaType;

    @Field(type = FieldType.Keyword)
    private String kind;

    @Field(type = FieldType.Text)
    private String summary;

    @Field(type = FieldType.Keyword)
    private String fileId;

    @Field(type = FieldType.Keyword)
    private String filename;

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

    /** Reserved for a future local media store (null while Telegram fileId is the source). */
    @Field(type = FieldType.Keyword)
    private String storageRef;
}
