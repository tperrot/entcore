package org.entcore.directory.services;

import fr.wseduc.webutils.Either;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;

public interface UserService {

	void createInClass(String classId, JsonObject user, Handler<Either<String, JsonObject>> result);

	void update(String id, JsonObject user, Handler<Either<String, JsonObject>> result);

	void sendUserCreatedEmail(HttpServerRequest request, String userId, Handler<Either<String, Boolean>> result);

	void get(String id, Handler<Either<String, JsonObject>> result);

}
