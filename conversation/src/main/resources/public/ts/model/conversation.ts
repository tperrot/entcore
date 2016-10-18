// Copyright © WebServices pour l'Éducation, 2014
//
// This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation (version 3 of the License).
//
// For the sake of explanation, any module that communicate over native
// Web protocols, such as HTTP, with ENT Core is outside the scope of this
// license and could be license under its own terms. This is merely considered
// normal use of ENT Core, and does not fall under the heading of "covered work".
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

import { http, idiom as lang, model, Model, notify, Collection } from 'entcore/entcore';
import { _ } from 'entcore/libs/underscore/underscore';
import { moment } from 'entcore/libs/moment/moment';

import { Folder, UserFolder, SystemFolder, SystemFolders, SystemFoldersCollection } from './folder';
import { User, Users, UsersCollection } from './user';
import { quota } from './quota';

export class Launcher {
    action: () => void;
    count: number;

    constructor(countdown, action) {
        this.count = countdown;
        this.action = action;
    }

    launch() {
        if (!--this.count) {
            this.action()
        }
    }
}

class Conversation extends Model {
    folders: SystemFoldersCollection;
    userFolders: Collection<UserFolder>;
    users: UsersCollection;
    systemFolders: string[];
    currentFolder: Folder;
    maxFolderDepth: number;

    constructor() {
        super();

        this.collection(User, new Users);
        this.collection(Folder, new SystemFolders());

        this.folders.inbox.countUnread();

        this.collection(UserFolder, {
            sync: function (hook: () => void) {
                http().get('folders/list').done((data) => {
                    this.load(data)
                    this.forEach(function (item) {
                        item.userFolders.sync()
                    })
                    if (typeof hook === 'function') {
                        hook();
                    }
                })
            }
        });

        http().get('max-depth').done((result) => {
            this.maxFolderDepth = parseInt(result['max-depth']);
            this.trigger('change');
        });
    }

    sync() {
        this.userFolders.sync();
        this.users.sync();
        quota.refresh();
    }
}

export let conversation = new Conversation();

model.build = function () {
    conversation.sync();
};


export * from './folder';
export * from './mail';
export * from './user';
export * from './quota';