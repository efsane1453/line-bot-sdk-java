/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.bot.spring.boot;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import com.linecorp.bot.client.LineMessagingService;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.MessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import com.linecorp.bot.model.message.TextMessage;
import com.linecorp.bot.spring.boot.IntegrationTest.MyController;
import com.linecorp.bot.spring.boot.annotation.LineBotMessages;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

// integration test
@RunWith(SpringRunner.class)
@SpringBootTest(classes = { IntegrationTest.class, MyController.class })
@WebAppConfiguration
@SpringBootApplication
public class IntegrationTest {

    static {
        System.setProperty("line.bot.channelSecret", "SECRET");
        System.setProperty("line.bot.channelToken", "TOKEN");
    }

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private LineMessagingService lineMessagingService;

    private MockMvc mockMvc;
    private static MockWebServer server;

    @RestController
    @Slf4j
    public static class MyController {
        @Autowired
        private LineMessagingService lineMessagingService;

        @PostMapping("/callback")
        public void callback(@NonNull @LineBotMessages List<Event> events) throws IOException {
            log.info("Got request: {}", events);

            for (Event event : events) {
                this.handleEvent(event);
            }
        }

        private void handleEvent(Event event) throws IOException {
            if (event instanceof MessageEvent) {
                MessageContent content = ((MessageEvent) event).getMessage();
                if (content instanceof TextMessageContent) {
                    String text = ((TextMessageContent) content).getText();
                    lineMessagingService.reply(
                            new ReplyMessage(((MessageEvent) event).getReplyToken(),
                                             new TextMessage(text)))
                                        .execute();
                }
            } else if (event instanceof FollowEvent) {
                lineMessagingService.reply(
                        new ReplyMessage(((FollowEvent) event).getReplyToken(),
                                         new TextMessage("follow")))
                                    .execute();
            }
        }
    }

    @BeforeClass
    public static void beforeClass() {
        server = new MockWebServer();
        System.setProperty("line.bot.apiEndPoint", server.url("/").toString());
    }

    @Before
    public void before() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                                      .build();
    }

    @Test
    public void missingSignatureTest() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/callback")
                                              .content("{}"))
               .andDo(print())
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("Missing 'X-Line-Signature' header")));
    }

    @Test
    public void validCallbackTest() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));

        String signature = "ECezgIpQNUEp4OSHYd7xGSuFG7e66MLPkCkK1Y28XTU=";

        InputStream resource = getClass().getClassLoader().getResourceAsStream("callback-request.json");
        byte[] json = IOUtils.toByteArray(resource);

        mockMvc.perform(MockMvcRequestBuilders.post("/callback")
                                              .header("X-Line-Signature", signature)
                                              .content(json))
               .andDo(print())
               .andExpect(status().isOk());

        // Test request 1
        RecordedRequest request1 = server.takeRequest();
        assertEquals("/v2/bot/message/reply", request1.getPath());
        assertEquals("Bearer TOKEN", request1.getHeader("Authorization"));
        assertEquals(
                "{\"replyToken\":\"nHuyWiB7yP5Zw52FIkcQobQuGDXCTA\",\"messages\":[{\"type\":\"text\",\"text\":\"Hello, world\"}]}",
                request1.getBody().readUtf8());

        // Test request 2
        RecordedRequest request2 = server.takeRequest();
        assertEquals("/v2/bot/message/reply", request2.getPath());
        assertEquals("Bearer TOKEN", request2.getHeader("Authorization"));
        assertEquals(
                "{\"replyToken\":\"nHuyWiB7yP5Zw52FIkcQobQuGDXCTA\",\"messages\":[{\"type\":\"text\",\"text\":\"follow\"}]}",
                request2.getBody().readUtf8());
    }
}
