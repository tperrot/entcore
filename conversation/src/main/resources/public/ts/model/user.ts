import { Model, http, idiom as lang, Collection } from 'entcore/entcore';
import { _ } from 'entcore/libs/underscore/underscore';

export class User extends Model {
    displayName: string;
    name: string;
    profile: string;
    id: string;

    constructor(data) {
        super(data);
    }

    toString() {
        return (this.displayName || '') + (this.name || '') + (this.profile ? ' (' + lang.translate(this.profile) + ')' : '')
    }

    findData(cb) {
        var that = this;
        http().get('/userbook/api/person?id=' + this.id).done(function (userData) {
            that.updateData({ id: that.id, displayName: userData.result[0].displayName });
            if (typeof cb === "function") {
                cb.call(that, userData.result[0]);
            }
        })
    }

    mapUser(displayNames, id) {
        return _.map(_.filter(displayNames, function (user) {
            return user[0] === id;
        }), function (user) {
            return new User({ id: user[0], displayName: user[1] });
        })[0];
    }
}

export class Users {
    filter: (filter: (item: User) => boolean) => User[];
    findWhere: (filter: any) => User;
    addRange: (data: User[], cb?: (item: User) => void, refreshView?: boolean) => void;
    sync: any;
    trigger: (event: string) => void;

    constructor() {
        this.sync = function() {
            http().get('/conversation/visible').done((data) => {
                _.forEach(data.groups, function (group) { group.isGroup = true });
                this.addRange(data.groups);
                this.addRange(data.users);
                this.trigger('sync');
            });
        };
    }

    findUser (search, include, exclude): User[] {
        var searchTerm = lang.removeAccents(search).toLowerCase();
        if (!searchTerm) {
            return [];
        }
        var found = _.filter(
            this.filter(function (user) {
                return _.findWhere(include, { id: user.id }) === undefined
            })
                .concat(include), function (user) {
                    var testDisplayName = '', testNameReversed = '';
                    if (user.displayName) {
                        testDisplayName = lang.removeAccents(user.displayName).toLowerCase();
                        testNameReversed = lang.removeAccents(user.displayName.split(' ')[1] + ' '
                            + user.displayName.split(' ')[0]).toLowerCase();
                    }
                    var testName = '';
                    if (user.name) {
                        testName = lang.removeAccents(user.name).toLowerCase();
                    }

                    return testDisplayName.indexOf(searchTerm) !== -1 ||
                        testNameReversed.indexOf(searchTerm) !== -1 ||
                        testName.indexOf(searchTerm) !== -1;
                }
        );
        return _.reject(found, function (element) {
            return _.findWhere(exclude, { id: element.id });
        });
    }

    isGroup (id) {
        return this.findWhere({ isGroup: true, id: id })
    }
}

export interface UsersCollection extends Users, Collection<User> { }