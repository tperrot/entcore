/*
 * Copyright © WebServices pour l'Éducation, 2016
 *
 * This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with ENT Core is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of ENT Core, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.entcore.auth.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.http.oauth.OpenIdConnectClient;
import fr.wseduc.webutils.request.CookieHelper;
import fr.wseduc.webutils.security.HmacSha1;
import org.entcore.auth.services.OpenIdConnectServiceProvider;
import org.vertx.java.core.Handler;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonElement;
import org.vertx.java.core.json.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.wseduc.webutils.Utils.isEmpty;

public class OpenIdConnectController extends AbstractFederateController {

	private static final String SCOPE_OPENID = "openid profile";
	private final Map<String, OpenIdConnectClient> openIdConnectClients = new HashMap<>();
	private OpenIdConnectServiceProvider openIdConnectServiceProvider;
	private JsonObject certificates = new JsonObject();
	private boolean subMapping;

	@Get("/openid/certs")
	public void certs(HttpServerRequest request) {
		renderJson(request, certificates);
	}

	@Get("/openid/login")
	public void login(HttpServerRequest request) {
		OpenIdConnectClient oic = getOpenIdConnectClient(request);
		if (oic == null) return;
		final String state = UUID.randomUUID().toString();
		CookieHelper.getInstance().setSigned("csrfstate", state, 900, request);
		oic.authorizeRedirect(request, state, SCOPE_OPENID);
	}

	@Get("/openid/authenticate")
	public void authenticate(final HttpServerRequest request) {
		OpenIdConnectClient oic = getOpenIdConnectClient(request);
		if (oic == null) return;
		final String state = CookieHelper.getInstance().getSigned("csrfstate", request);
		if (state == null) {
			forbidden(request, "invalid_state");
			return;
		}
		oic.authorizationCodeToken(request, state, true, new Handler<JsonObject>() {
			@Override
			public void handle(final JsonObject payload) {
				if (payload != null) {
					openIdConnectServiceProvider.executeFederate(payload, new Handler<Either<String, JsonElement>>() {
						@Override
						public void handle(Either<String, JsonElement> res) {
							if (res.isRight() && res.right().getValue().isObject()) {
								authenticate(res.right().getValue().asObject(), null, null, request);
							} else if (subMapping && res.isLeft() && OpenIdConnectServiceProvider.UNRECOGNIZED_USER_IDENTITY
									.equals(res.left().getValue())) {
								final String p = payload.encode();
								try {
									JsonObject params = new JsonObject().putString("payload", p)
											.putString("key", HmacSha1.sign(p, signKey));
									renderView(request, params, "mappingFederatedUser.html", null);
								} catch (Exception e) {
									log.error("Error loading mapping openid connect identity.", e);
									renderError(request);
								}
							} else {
								forbidden(request, "invalid.payload");
							}
						}
					});
				} else {
					forbidden(request, "invalid_token");
				}
			}
		});
	}

	@Post("/openid/mappingUser")
	public void mappingUser(final HttpServerRequest request) {
		if (!subMapping) {
			forbidden(request, "unauthorized.sub.mapping");
			return;
		}
		request.expectMultiPart(true);
		request.endHandler(new VoidHandler() {
			@Override
			protected void handle() {
				final String login = request.formAttributes().get("login");
				final String password = request.formAttributes().get("password");
				final String payload = request.formAttributes().get("payload");
				final String key = request.formAttributes().get("key");
				try {
					if (isEmpty(login) || isEmpty(password) || isEmpty(payload) || isEmpty(key) ||
							!key.equals(HmacSha1.sign(payload, signKey))) {
						badRequest(request, "invalid.attribute");
						return;
					}
					openIdConnectServiceProvider.mappingUser(login, password, new JsonObject(payload),
							new Handler<Either<String, JsonElement>>() {
						@Override
						public void handle(Either<String, JsonElement> event) {
							if (event.isRight()) {
								authenticate(event.right().getValue().asObject(), null, null, request);
							} else {
								forbidden(request, "invalid.sub.mapping");
							}
						}
					});
				} catch (Exception e) {
					log.error("Error mapping OpenId Connect user.", e);
					badRequest(request, "invalid.attribute");
				}
			}
		});
	}

	private OpenIdConnectClient getOpenIdConnectClient(HttpServerRequest request) {
		OpenIdConnectClient oic = openIdConnectClients.get(getHost(request));
		if (oic == null) {
			forbidden(request, "invalid.federate.domain");
			return null;
		}
		return oic;
	}

	public void addClient(String domain, OpenIdConnectClient client) {
		openIdConnectClients.put(domain, client);
	}

	public void setOpenIdConnectServiceProvider(OpenIdConnectServiceProvider openIdConnectServiceProvider) {
		this.openIdConnectServiceProvider = openIdConnectServiceProvider;
	}

	public void setCertificates(JsonObject certificates) {
		this.certificates = certificates;
	}

	public void setSubMapping(boolean subMapping) {
		this.subMapping = subMapping;
	}

}
