import {Model, http, Collection, notify } from 'entcore/entcore';
import { _ } from 'entcore/libs/underscore/underscore';
import { conversation } from './conversation';
import { Mail, Mails, MailsCollection } from './mail';
import { quota } from './quota';

export class Folder extends Model {
    pageNumber: number;
    mails: MailsCollection;
    nbUnread: number;
    userFolders: Collection<UserFolder>;

    nextPage() {
        if (!this.mails.full) {
            this.pageNumber++;
            this.mails.sync({ pageNumber: this.pageNumber, emptyList: false });
        }
    }
}

export class SystemFolder extends Folder {
    folderName: string;

    constructor(api) {
        super(api);

        var thatFolder = this
        this.pageNumber = 0;

        this.collection(Mail, new Mails(api));
    }
}

export class Trash extends SystemFolder {
    constructor() {
        super({
            get: '/conversation/list/trash'
        });

        this.folderName = 'trash';

        this.collection(UserFolder, {
            sync: function () {
                var that = this
                http().get('folders/list?trash=').done(function (data) {
                    that.load(data)
                })
            }
        });
    }

    restoreMails () {
        http().put('/conversation/restore?' + http().serialize({
            id: _.pluck(this.mails.selection(), 'id')
        }));
        this.mails.removeSelection();
        conversation.folders.inbox.mails.refresh();
        conversation.folders.inbox.mails.refresh();
        conversation.folders.inbox.mails.refresh();
    }

    removeMails () {
        var request = http().delete('/conversation/delete?' + http().serialize({
            id: _.pluck(this.mails.selection(), 'id')
        }));
        this.mails.removeSelection()
        return request;
    }
}

export class Inbox extends SystemFolder {
    constructor() {
        super({
            get: '/conversation/list/inbox'
        });

        this.folderName = 'inbox';
    }

    countUnread () {
        http().get('/conversation/count/INBOX', { unread: true }).done((data) => {
            this.nbUnread = parseInt(data.count);
        });
    }
}

export class Draft extends SystemFolder {
    constructor() {
        super({
            get: '/conversation/list/draft'
        });

        this.folderName = 'draft';
    }

    saveDraft(draft: Mail): Promise<any> {
        return new Promise((resolve, reject) => {
            draft.saveAsDraft().then(() => {
                this.mails.push(draft);
                resolve();
            });
        });
    }

    transfer(mail: Mail) {
        this.saveDraft(mail).then((id) => {
            http().put("message/" + mail.id + "/forward/" + mail.id).done(function () {
                for (var i = 0; i < mail.attachments.length; i++) {
                    mail.attachments.push(JSON.parse(JSON.stringify(mail.attachments[i])))
                }
                quota.refresh();
            }).error(function (data) {
                notify.error(data.error)
            })
        });
    }
}

export class Outbox extends SystemFolder {
    constructor() {
        super({
            get: '/conversation/list/outbox'
        });

        this.folderName = 'outbox';
    }
}

export class UserFolder extends Folder {
    id: string;
    name: string;
    parentFolderId: string;
    parentFolder: UserFolder;

    constructor(data?) {
        super(data);

        var thatFolder = this
        this.pageNumber = 0;

        this.collection(UserFolder, {
            sync: function (hook?: () => void) {
                var that = this
                http().get('folders/list?parentId=' + thatFolder.id).done(function (data) {
                    _.forEach(data, function (item) {
                        item.parentFolder = thatFolder
                    })
                    that.load(data)
                    that.forEach(function (item) {
                        item.userFolders.sync()
                    })

                    if (typeof hook === 'function') {
                        hook();
                    }
                    
                })
            }
        })

        this.collection(Mail, {
            refresh: function () {
                this.pageNumber = 0;
                this.sync();
            },
            sync: function (pageNumber, emptyList) {
                if (!pageNumber) {
                    pageNumber = 0;
                }
                var that = this;
                http().get('/conversation/list/' + thatFolder.id + '?restrain=&page=' + pageNumber).done(function (data) {
                    data.sort(function (a, b) { return b.date - a.date })
                    if (emptyList === false) {
                        that.addRange(data);
                        if (data.length === 0) {
                            that.full = true;
                        }
                    }
                    else {
                        that.load(data);
                    }
                });
            },
            removeMails: function () {
                http().put('/conversation/trash?' + http().serialize({ id: _.pluck(this.selection(), 'id') })).done(function () {
                    conversation.folders.trash.mails.refresh();
                });
                this.removeSelection();
            },
            moveSelection: function (destinationFolder) {
                http().put('move/userfolder/' + destinationFolder.id + '?' + http().serialize({ id: _.pluck(this.selection(), 'id') })).done(function () {
                    conversation.currentFolder.mails.refresh();
                });
            },
            removeFromFolder: function () {
                return http().put('move/root?' + http().serialize({ id: _.pluck(this.selection(), 'id') }))
            }
        });
    }

    depth(): number {
        var depth = 1;
        var ancestor = this.parentFolder;
        while (ancestor) {
            ancestor = ancestor.parentFolder;
            depth = depth + 1;
        }
        return depth;
    }
    
    create() {
        var json = !this.parentFolderId ? {
            name: this.name
        } : {
                name: this.name,
                parentId: this.parentFolderId
            }

        return http().postJson('folder', json);
    }

    update() {
        var json = {
            name: this.name
        }
        return http().putJson('folder/' + this.id, json)
    }

    trash() {
        return http().put('folder/trash/' + this.id)
    }

    restore() {
        return http().put('folder/restore/' + this.id)
    }

    delete() {
        return http().delete('folder/' + this.id)
    }
}

export class SystemFolders {
    sync: any;
    inbox: Inbox;
    trash: Trash;
    outbox: Outbox;
    draft: Draft;
    pageNumber: number;
    systemFolders: string[];

    constructor() {
        this.sync = () => {
            if (conversation.currentFolder === null) {
                conversation.currentFolder = this.inbox;
            }

            conversation.currentFolder.mails.sync({ pageNumber: this.pageNumber });
        };

        this.inbox = new Inbox();
        this.trash = new Trash();
        this.draft = new Draft();
        this.outbox = new Outbox();
    }
    
    openFolder (folderName) {
        conversation.currentFolder = this[folderName];
        conversation.currentFolder.mails.sync();
        conversation.currentFolder.pageNumber = 0;
        conversation.currentFolder.mails.full = false;
        if (conversation.currentFolder instanceof Trash) {
            (conversation.currentFolder as Trash).userFolders.sync()
        }
        conversation.currentFolder.trigger('change');
    }
}

export interface SystemFoldersCollection extends Collection<SystemFolder>, SystemFolders { }