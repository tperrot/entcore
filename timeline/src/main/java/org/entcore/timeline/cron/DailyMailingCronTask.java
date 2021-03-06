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

package org.entcore.timeline.cron;

import org.entcore.common.notification.TimelineMailer;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import fr.wseduc.webutils.Either;

public class DailyMailingCronTask implements Handler<Long> {

	private static final Logger log = LoggerFactory.getLogger(DailyMailingCronTask.class);
	private final TimelineMailer mailer;
	private final int dayDelta;

	public DailyMailingCronTask(TimelineMailer mailer, int dayDelta){
		this.mailer = mailer;
		this.dayDelta = dayDelta;
	}

	@Override
	public void handle(Long event) {
		log.info("[Daily mailing] Starting ...");
		mailer.sendDailyMails(dayDelta, new Handler<Either<String,JsonObject>>() {
			public void handle(Either<String, JsonObject> event) {
				if(event.isLeft()){
					log.error("[Daily mailing] Error encountered : " + event.left().getValue());
				} else {
					log.info("[Daily mailing] Completed : " + event.right().getValue().encodePrettily());
				}
			}
		});
	}

}
