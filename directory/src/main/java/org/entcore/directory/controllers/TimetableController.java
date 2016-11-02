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

package org.entcore.directory.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.directory.services.TimetableService;
import org.joda.time.DateTime;
import org.vertx.java.core.http.HttpServerRequest;

import static fr.wseduc.webutils.Utils.getOrElse;
import static org.entcore.common.http.response.DefaultResponseHandler.arrayResponseHandler;

public class TimetableController extends BaseController {

	private TimetableService timetableService;

	@Get("/timetable/courses/:structureId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	public void listCourses(HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		long lastDate;
		try {
			lastDate = Long.parseLong(getOrElse(request.params().get("lastDate"), "0", false));
		} catch (NumberFormatException e) {
			try {
				lastDate = DateTime.parse(request.params().get("lastDate")).getMillis();
			} catch (RuntimeException e2) {
				badRequest(request, "invalid.date");
				return;
			}
		}
		timetableService.listCourses(structureId, lastDate, arrayResponseHandler(request));
	}

	@Get("/timetable/subjects/:structureId")
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	@ResourceFilter(SuperAdminFilter.class)
	public void listSubjects(HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		final boolean teachers = request.params().contains("teachers");
		final boolean classes = request.params().contains("classes");
		final boolean groups = request.params().contains("groups");
		timetableService.listSubjects(structureId, teachers, classes, groups, arrayResponseHandler(request));
	}

	public void setTimetableService(TimetableService timetableService) {
		this.timetableService = timetableService;
	}

}
