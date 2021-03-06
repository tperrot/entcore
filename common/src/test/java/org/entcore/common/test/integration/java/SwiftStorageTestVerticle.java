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

package org.entcore.common.test.integration.java;

import org.entcore.common.storage.Storage;
import org.entcore.common.storage.StorageFactory;
import org.junit.Test;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;
import org.vertx.testtools.TestVerticle;

public class SwiftStorageTestVerticle extends TestVerticle {

	private StorageTests storageTests;

	@Override
	public void start() {
		JsonObject config = new JsonObject().putObject("swift", new JsonObject()
				.putString("uri", "")
				.putString("container", "")
				.putString("user", "")
				.putString("key","")
		);
		Storage s = new StorageFactory(vertx, config).getStorage();
		storageTests = new StorageTests(s);
		vertx.setTimer(5000l, new Handler<Long>() {
			@Override
			public void handle(Long event) {
				SwiftStorageTestVerticle.super.start();
			}
		});
	}

	@Test
	public void statsTest() {
		storageTests.statsTest();
	}

}
