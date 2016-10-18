import { http, model, Model, idiom as lang } from 'entcore/entcore';

class Quota extends Model {
    max: number;
    used: number;
    unit: string;

    constructor() {
        super();
        this.max = 1;
        this.used = 0;
        this.unit = 'Mo';
    }

    appropriateDataUnit(bytes: number) {
        var order = 0
        var orders = {
            0: lang.translate("byte"),
            1: "Ko",
            2: "Mo",
            3: "Go",
            4: "To"
        }
        var finalNb = bytes
        while (finalNb >= 1024 && order < 4) {
            finalNb = finalNb / 1024
            order++
        }
        return {
            nb: finalNb,
            order: orders[order]
        }
    }

    refresh () {
        http().get('/workspace/quota/user/' + model.me.userId).done((data) => {
            //to mo
            data.quota = data.quota / (1024 * 1024);
            data.storage = data.storage / (1024 * 1024);

            if (data.quota > 2000) {
                data.quota = Math.round((data.quota / 1024) * 10) / 10;
                data.storage = Math.round((data.storage / 1024) * 10) / 10;
                this.unit = 'Go';
            }
            else {
                data.quota = Math.round(data.quota);
                data.storage = Math.round(data.storage);
            }

            this.max = data.quota;
            this.used = data.storage;
            this.trigger('change');
        });
    }
};

export let quota = new Quota();