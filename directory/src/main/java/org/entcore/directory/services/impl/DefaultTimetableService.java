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

package org.entcore.directory.services.impl;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.Either;
import fr.wseduc.webutils.Utils;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.directory.services.TimetableService;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import static org.entcore.common.mongodb.MongoDbResult.validResultsHandler;
import static org.entcore.common.neo4j.Neo4jResult.validResultHandler;

public class DefaultTimetableService implements TimetableService {

	private static final String COURSES = "courses";

	@Override
	public void listCourses(String structureId, long lastDate, Handler<Either<String, JsonArray>> handler) {
		if (Utils.validationParamsNull(handler, structureId)) return;
		final JsonObject query = new JsonObject().putString("structureId", structureId);
		final JsonObject sort = new JsonObject().putNumber("startDate", 1);
		final JsonObject keys = new JsonObject()
				.putNumber("_id", 1)
				.putNumber("structureId", 1)
				.putNumber("subjectId", 1)
				.putNumber("roomLabels", 1)
				.putNumber("equipmentLabels", 1)
				.putNumber("teacherIds", 1)
				.putNumber("personnelIds", 1)
				.putNumber("classes", 1)
				.putNumber("groups", 1)
				.putNumber("dayOfWeek", 1)
				.putNumber("startDate", 1)
				.putNumber("endDate", 1)
				.putNumber("subjectId", 1)
				.putNumber("roomLabels", 1);
		if (lastDate > 0) {
			query.putArray("$or", new JsonArray()
					.addObject(new JsonObject().putObject("modified", new JsonObject().putNumber("$gte", lastDate)))
					.addObject(new JsonObject().putObject("deleted", new JsonObject().putNumber("$gte", lastDate))));
			keys.putNumber("deleted", 1);
		} else {
			query.putObject("deleted", new JsonObject().putBoolean("$exists", false));
		}
		MongoDb.getInstance().find(COURSES, query, sort, keys, validResultsHandler(handler));
	}

	@Override
	public void listSubjects(String structureId, boolean teachers, boolean classes, boolean groups,
			Handler<Either<String, JsonArray>> handler) {
		if (Utils.validationParamsNull(handler, structureId)) return;
		final JsonObject params = new JsonObject().putString("id", structureId);
		StringBuilder query = new StringBuilder();
		query.append("MATCH (:Structure {id:{id}})<-[:SUBJECT]-(sub:Subject)");
		if (teachers) {
			query.append("<-[r:TEACHES]-(u:User)");
		}
		query.append(" RETURN sub.id as subjectId, sub.code as subjectCode, sub.label as subjectLabel");
		if (teachers) {
			query.append(", u.id as teacherId");
			if (classes) {
				query.append(", r.classes as classes");
			}
			if (groups) {
				query.append(", r.groups as groups");
			}
		}
		Neo4j.getInstance().execute(query.toString(), params, validResultHandler(handler));
	}

}
