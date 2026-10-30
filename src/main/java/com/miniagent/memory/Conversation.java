package com.miniagent.memory;

import com.fasterxml.jackson.databind.node.ArrayNode;

/** 一段会话的短期记忆，内容就是发给模型的 messages。 */
public final class Conversation {

    private final String id;
    private final ArrayNode messages;

    public Conversation(String id, ArrayNode messages) {
        this.id = id;
        this.messages = messages;
    }

    public String id() {
        return id;
    }

    /** 调用方需要先拿到这把锁，再改 messages。 */
    public ArrayNode messages() {
        return messages;
    }

    public synchronized ArrayNode snapshot() {
        return messages.deepCopy();
    }
}
