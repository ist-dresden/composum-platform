/**
 *
 *
 */
(function (window) {
    'use strict';

    window.login = window.login || {};

    (function (login) {

        /**
         * the authorization hook in case of a failed request: this handler must be registered
         * in the core module by each page which should support this feature
         */
        login.authorize = function (retryThisFailedCall) {
            // TODO: platform authorize feature
        };

        login.UserLoginForm = Backbone.View.extend({

            initialize: function (options) {
                var href = new RegExp('(https?://[^/]+)/.*[\\?&]resource=([^&#]*)').exec(window.location.href);
                if (href) {
                    this.baseUrl = href[1];
                    this.resource = decodeURIComponent(href[2]);
                    while (this.resource.indexOf('%') === 0) { // ? multiple encodings...
                        this.resource = decodeURIComponent(this.resource);
                    }
                }
                var acm = new RegExp('(https?://[^/]+)/.*[\\?&]accessmode=([^&#]*)').exec(window.location.href);
                if (acm) {
                    this.accessmode = acm;
                }
                this.$alert = this.$('.alert');
                this.$submit = this.$('.buttons .login');
                this.$submit.click(_.bind(this.login, this));
                this.alert();
            },

            login: function (event) {
                event.preventDefault();
                $.ajax({
                    type: 'POST',
                    url: this.$el.attr("action"),
                    data: new FormData(this.$el[0]),
                    cache: false,
                    contentType: false,
                    processData: false,
                    complete: _.bind(function (result, statusText, thrownError) {
                        if (result.status === 0 // FIXME: interim fix for the insecure redirect
                            || (result.status >= 200 && result.status < 400)) {
                            this.alert();
                            var target = this.resource ? this.resource : '/';
                            if (this.accessmode) {
                                target = target + '?accessmode=' + this.accessmode;
                            }
                            window.location.href = target;
                        } else {
                            this.alert('Danger', result.responseText ? result.responseText : thrownError);
                        }
                    }, this)
                });
                return false;
            },

            alert: function (type, message) {
                this.$alert.removeClass();
                if (message) {
                    this.$alert.html(message);
                    this.$alert.addClass('alert').addClass('alert-' + type);
                } else {
                    this.$alert.html('');
                    this.$alert.addClass('alert').addClass('alert-hidden');
                }
            }
        });

        login.userLoginForm = new login.UserLoginForm({el: '.login-page form'});

    })(window.login);

})(window);
