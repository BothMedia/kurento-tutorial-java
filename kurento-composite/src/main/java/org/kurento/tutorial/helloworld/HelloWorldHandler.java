/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.kurento.tutorial.helloworld;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Hello World handler (application and media logic).
 *
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @author David Fernandez (d.fernandezlop@gmail.com)
 * @since 6.0.0
 */
public class HelloWorldHandler extends TextWebSocketHandler {

  private static final Gson gson = new GsonBuilder().create();
  private final Logger log = LoggerFactory.getLogger(HelloWorldHandler.class);

  @Autowired
  private KurentoClient kurento;
  private MediaPipeline pipeline;
  private WebRtcEndpoint webRtcEndpoint;
  private Composite mixer;
  private HubPort outHubPort;
  private HubPort webCamHubPort;
  private PlayerEndpoint extra;
  private PlayerEndpoint longVideo;

  private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();

	@Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);

    log.debug("Incoming message: {}", jsonMessage);

    switch (jsonMessage.get("id").getAsString()) {
      case "start":
        start(session, jsonMessage);
        break;
      case "addA":
        add(MediaType.AUDIO);
        break;
      case "addV":
        add(MediaType.VIDEO);
        break;
      case "add":
        add(null);
        break;
      case "stop": {
        UserSession user = users.remove(session.getId());
        if (user != null) {
          user.release();
        }
        stopAll();
        break;
      }
      case "onIceCandidate": {
        JsonObject jsonCandidate = jsonMessage.get("candidate").getAsJsonObject();

        UserSession user = users.get(session.getId());
        if (user != null) {
          IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
              jsonCandidate.get("sdpMid").getAsString(),
              jsonCandidate.get("sdpMLineIndex").getAsInt());
          user.addCandidate(candidate);
        }
        break;
      }
      default:
        sendError(session, "Invalid message with id " + jsonMessage.get("id").getAsString());
        break;
    }
  }

private void stopAll() {

	if(webRtcEndpoint!=null) {
		webRtcEndpoint.release();
		webRtcEndpoint = null;
	}

	if(mixer!=null) {
		mixer.release();
		mixer = null;
	}
	if(outHubPort!=null) {
		outHubPort.release();
		outHubPort = null;
	}
	if(extra!=null) {
		extra.release();
		extra = null;
	}
	if(longVideo!=null) {
		longVideo.release();
		longVideo = null;
	}

	if(pipeline!=null) {
		pipeline.release();
		pipeline = null;
	}
}

  private void add(MediaType mediaType) {
	if(extra == null) {
		extra = new PlayerEndpoint.Builder(pipeline,"http://www.sample-videos.com/video/mp4/360/big_buck_bunny_360p_20mb.mp4").build();
		final HubPort videoHubPort = new HubPort.Builder(mixer).build();

		if(mediaType==null) {
			extra.connect(videoHubPort);
		} else  {
			extra.connect(videoHubPort,mediaType);
		}

		extra.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
			@Override
			public void onEvent(EndOfStreamEvent endOfStreamEvent) {
				extra = null;
				videoHubPort.release();
			}
		});

		extra.play();
	}
  }

	private void longVideoStart(MediaType mediaType) {
		if(longVideo == null) {
			longVideo = new PlayerEndpoint.Builder(pipeline,"http://www.sample-videos.com/video/mp4/360/big_buck_bunny_360p_20mb.mp4").build();
			final HubPort videoHubPort = new HubPort.Builder(mixer).build();

			longVideo.connect(videoHubPort);

			longVideo.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
				@Override
				public void onEvent(EndOfStreamEvent endOfStreamEvent) {
					longVideo = null;
					videoHubPort.release();
					HelloWorldHandler.this.longVideoStart(null);
				}
			});

			longVideo.play();


		}
	}


	private void start(final WebSocketSession session, JsonObject jsonMessage) {
		try {
			pipeline = kurento.createMediaPipeline();

			// 1. Media logic (webRtcEndpoint in loopback)
			webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
			mixer = new Composite.Builder(pipeline).build();

			outHubPort = new HubPort.Builder(mixer).build();

			outHubPort.connect(webRtcEndpoint);

			longVideoStart(null);


			// 2. Store user session
			UserSession user = new UserSession();
			user.setMediaPipeline(pipeline);
			user.setWebRtcEndpoint(webRtcEndpoint);
			users.put(session.getId(), user);

			// 3. SDP negotiation
			String sdpOffer = jsonMessage.get("sdpOffer").getAsString();
			String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

			JsonObject response = new JsonObject();
			response.addProperty("id", "startResponse");
			response.addProperty("sdpAnswer", sdpAnswer);

			synchronized (session) {
				session.sendMessage(new TextMessage(response.toString()));
			}

			// 4. Gather ICE candidates
			webRtcEndpoint.addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {

			@Override
			public void onEvent(IceCandidateFoundEvent event) {
				JsonObject response = new JsonObject();
				response.addProperty("id", "iceCandidate");
				response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
				try {
					synchronized (session) {
					session.sendMessage(new TextMessage(response.toString()));
					}
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			});

			webRtcEndpoint.gatherCandidates();

		} catch (Throwable t) {
			sendError(session, t.getMessage());
		}
  }

  private void sendError(WebSocketSession session, String message) {
    try {
      JsonObject response = new JsonObject();
      response.addProperty("id", "error");
      response.addProperty("message", message);
      session.sendMessage(new TextMessage(response.toString()));
    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }
}
