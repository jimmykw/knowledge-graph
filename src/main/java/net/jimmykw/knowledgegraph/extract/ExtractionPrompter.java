package net.jimmykw.knowledgegraph.extract;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.util.JsonPrompter;

public class ExtractionPrompter extends JsonPrompter<ExtractionResult> {

    public ExtractionPrompter(ChatClient chatClient, BeanOutputConverter<ExtractionResult> converter) {
        super(chatClient, converter);
    }
}
