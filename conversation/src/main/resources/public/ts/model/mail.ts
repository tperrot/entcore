import { Collection, Model, http, model, notify, idiom as lang } from 'entcore/entcore';
import { moment } from 'entcore/libs/moment/moment';
import { _ } from 'entcore/libs/underscore/underscore';

import { User } from './user';
import { conversation } from './conversation';
import { quota } from './quota';
import { SystemFolder } from './folder';

export class Attachment {
    file: File;
    progress: {
        total: number,
        completion: number
    };
    id: string;
    filename: string;
    size: number;
    contentType: string;

    constructor(file: File) {
        this.file = file;
        this.progress = {
            total: 100,
            completion: 0
        }
    }
}

export class Mail extends Model {
    id: string;
    date: string;
    displayNames: string[];
    from: string;
    subject: string;
    body: string;
    to: User[];
    cc: User[];
    unread: boolean;
    parentConversation: Mail;
    newAttachments: FileList;
    loadingAttachments: Attachment[];
    attachments: Attachment[];

    constructor(data?) {
        super(data);
        this.loadingAttachments = [];
        this.attachments = [];
    }

    setMailContent(origin: Mail, mailType: string, copyReceivers?: boolean) {
        if (origin.subject.indexOf(format[mailType].prefix) === -1) {
            this.subject = lang.translate(format[mailType].prefix) + origin.subject;
        }
        else {
            this.subject = origin.subject;
        }

        if (copyReceivers) {
            this.cc = origin.cc;
            this.to = origin.to;
        }
        this.body = format[mailType].content + '<blockquote>' + origin.body + '</blockquote>';
    }

    sentDate() {
        return moment(parseInt(this.date)).calendar();
    };

    longDate() {
        return moment(parseInt(this.date)).format('dddd DD MMMM YYYY')
    };

    sender() {
        var that = this;
        return User.prototype.mapUser(this.displayNames, this.from);
    };

    map(id) {
        if (id instanceof User) {
            return id;
        }
        return User.prototype.mapUser(this.displayNames, id);
    };

    saveAsDraft(): Promise<any> {
        return new Promise((resolve, reject) => {
            var that = this;
            var data: any = { subject: this.subject, body: this.body };
            data.to = _.pluck(this.to, 'id');
            data.cc = _.pluck(this.cc, 'id');
            if (!data.subject) {
                data.subject = lang.translate('nosubject');
            }
            var path = '/conversation/draft';
            if (this.id) {
                http().putJson(path + '/' + this.id, data).done(function (newData) {
                    that.updateData(newData);
                    conversation.folders.draft.mails.refresh();
                    resolve();
                });
            }
            else {
                if (this.parentConversation) {
                    path += '?In-Reply-To=' + this.parentConversation.id;
                }
                http().postJson(path, data).done(function (newData) {
                    that.updateData(newData);
                    conversation.folders.draft.mails.refresh();
                    resolve();
                });
            }
        });
    };

    send() {
        var data: any = { subject: this.subject, body: this.body };
        data.to = _.pluck(this.to, 'id');
        data.cc = _.pluck(this.cc, 'id');
        if (data.to.indexOf(model.me.userId) !== -1) {
            conversation.folders['inbox'].nbUnread++;
        }
        if (data.cc.indexOf(model.me.userId) !== -1) {
            conversation.folders['inbox'].nbUnread++;
        }
        var path = '/conversation/send?';
        if (!data.subject) {
            data.subject = lang.translate('nosubject');
        }
        if (this.id) {
            path += 'id=' + this.id + '&';
        }
        if (this.parentConversation) {
            path += 'In-Reply-To=' + this.parentConversation.id;
        }
        http().postJson(path, data).done(function (result) {
            conversation.folders['outbox'].mails.refresh();
            conversation.folders['draft'].mails.refresh();

            if (parseInt(result.sent) > 0) {
                notify.info('mail.sent');
            }
            var inactives = '';
            result.inactive.forEach(function (name) {
                inactives += name + lang.translate('invalid') + '<br />';
            });
            if (result.inactive.length > 0) {
                notify.info(inactives);
            }
            var undelivered = result.undelivered.join(', ');
            if (result.undelivered.length > 0) {
                notify.error(undelivered + lang.translate('undelivered'));
            }
        }).e400(function (e) {
            var error = JSON.parse(e.responseText);
            notify.error(error.error);
        });
    };

    open(cb) {
        var that = this;

        this.unread = false;
        http().getJson('/conversation/message/' + this.id).done(function (data) {
            that.updateData(data);
            that.to = _.map(that.to, function (user) {
                var n = '';
                var foundUser = _.find(that.displayNames, function (name) {
                    return name[0] === user;
                });
                if (foundUser) {
                    n = foundUser[1];
                }
                return new User({
                    id: user,
                    displayName: n
                })
            });

            that.cc = _.map(that.cc, function (user) {
                return new User({
                    id: user,
                    displayName: _.find(that.displayNames, function (name) {
                        return name[0] === user;
                    })[1]
                })
            });

            conversation.folders['inbox'].countUnread();
            conversation.currentFolder.mails.refresh();

            if (typeof cb === 'function') {
                cb();
            }
        });
    };

    remove() {
        if ((conversation.currentFolder as SystemFolder).folderName !== 'trash') {
            http().put('/conversation/trash?id=' + this.id).done(function () {
                conversation.currentFolder.mails.refresh();
                conversation.folders['trash'].mails.refresh();
            });
        }
        else {
            http().delete('/conversation/delete?id=' + this.id).done(function () {
                conversation.folders['trash'].mails.refresh();
            });
        }
    };

    removeFromFolder() {
        return http().put('move/root?id=' + this.id)
    }

    move(destinationFolder) {
        http().put('move/userfolder/' + destinationFolder.id + '?id=' + this.id).done(function () {
            conversation.currentFolder.mails.refresh();
        });
    }

    trash() {
        http().put('/conversation/trash?id=' + this.id).done(function () {
            conversation.currentFolder.mails.refresh();
        });
    }

    postAttachment(attachment, options) {
        return http().postFile("message/" + this.id + "/attachment", attachment, options)
    }

    postAttachments() {
        _.forEach(this.newAttachments, (targetAttachment) => {
            let attachmentObj = new Attachment(targetAttachment);
            this.loadingAttachments.push(attachmentObj)

            let formData = new FormData()
            formData.append('file', attachmentObj.file)

            this.postAttachment(formData, {
                xhr: () => {
                    var xhr = new XMLHttpRequest();

                    xhr.upload.addEventListener("progress", (e: ProgressEvent) => {
                        if (e.lengthComputable) {
                            var percentage = Math.round((e.loaded * 100) / e.total);
                            attachmentObj.progress.completion = percentage;
                            this.trigger('change');
                        }
                    }, false);

                    return xhr;
                },
                complete: () => {
                    this.loadingAttachments.splice(this.loadingAttachments.indexOf(attachmentObj), 1)
                    this.trigger('change');
                }
            }).done((result) => {
                attachmentObj.id = result.id;
                attachmentObj.filename = attachmentObj.file.name;
                attachmentObj.size = attachmentObj.file.size;
                attachmentObj.contentType = attachmentObj.file.type;
                this.attachments.push(attachmentObj)
                quota.refresh();
            }).e400((e) => {
                var error = JSON.parse(e.responseText);
                notify.error(error.error);
            })
        })
    }
    

    deleteAttachment(attachment) {
        this.attachments.splice(this.attachments.indexOf(attachment), 1);
        http().delete("message/" + this.id + "/attachment/" + attachment.id)
            .done(() => {
                quota.refresh();
            });
    }
}

export class Mails {
    pageNumber: number;
    api: any;
    sync: any;
    full: boolean;
    removeSelection: () => void;
    addRange: (data: Mail[], cb?: (item: Mail) => void, refreshView?: boolean) => void;
    load: (data: Mail[], cb?: (item: Mail) => void, refreshView?: boolean) => void;
    selection: () => Mail[];

    constructor(api) {
        this.api = api;
        this.sync = function (data?: { pageNumber?: number, emptyList?: boolean }): any {
            if (!data) {
                data = {};
            }
            if (!data.pageNumber) {
                data.pageNumber = 0;
            }

            return http().get(this.api.get + '?page=' + data.pageNumber).done((mails) => {
                mails.sort(function (a, b) { return b.date - a.date })
                if (data.emptyList === false) {
                    this.addRange(mails);
                    if (mails.length === 0) {
                        this.full = true;
                    }
                }
                else {
                    this.load(mails);
                }
            });
        };
    }

    refresh() {
        this.pageNumber = 0;
        return this.sync();
    }

    removeMails() {
        http().put('/conversation/trash?' + http().serialize({ id: _.pluck(this.selection(), 'id') })).done(function () {
            conversation.folders.trash.mails.refresh();
            quota.refresh();
        });
        this.removeSelection();
    }

    moveSelection(destinationFolder) {
        http().put('move/userfolder/' + destinationFolder.id + '?' + http().serialize({ id: _.pluck(this.selection(), 'id') })).done(function () {
            conversation.currentFolder.mails.refresh();
        });
    }
}

let mailFormat = {
    reply: {
        prefix: 'reply.re',
        content: ''
    },
    transfer: {
        prefix: 'reply.fw',
        content: ''
    }
};

http().get('/conversation/public/template/mail-content/transfer.html').done(function (content) {
    format.transfer.content = content;
});

http().get('/conversation/public/template/mail-content/reply.html').done(function (content) {
    format.reply.content = content;
});

export let format = mailFormat;
export interface MailsCollection extends Collection<Mail>, Mails { }