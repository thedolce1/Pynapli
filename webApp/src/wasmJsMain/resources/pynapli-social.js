/*
 * "Continue with Google / Apple" for the web companion (ONBOARDING_AUTH_SPEC, Section 5 of the
 * onboarding + first grid plan). Loaded by WebSocialSignInProvider only when the server's
 * /v1/auth/social/config switches a provider on AND index.html carries that provider's client id;
 * with the feature off, no third-party script is ever requested.
 *
 * Why a small DOM layer instead of a Compose button doing everything:
 *  - Google Identity Services issues ID tokens only from its own rendered button (or One Tap, which
 *    has cooldowns and can be suppressed); there is no documented way to start the button flow
 *    from script (developers.google.com/identity/gsi/web/reference/js-reference). The nonce must be
 *    set in initialize() before that click, so the Compose tap first fetches a fresh nonce, then
 *    this layer shows Google's own button initialised with it.
 *  - Sign in with Apple JS opens a popup, which browsers only allow from a live user gesture; the
 *    nonce fetch after the Compose tap can outlive that gesture (Safari's is short). The layer's
 *    Apple button is clicked directly, so signIn() runs inside its own click.
 * Both layers show the provider's official button, so brand rules are met by the providers' code.
 *
 * Contract with Kotlin (webApp/.../auth/WebSocialSignInProvider.kt): every request ends in exactly
 * one done(kind, idToken, authorizationCode, givenName, message) call, kind one of "success",
 * "cancelled", "unavailable", "error"; absent values are "". Nothing here logs or stores a token.
 */
(function () {
    'use strict';

    var GIS_SRC = 'https://accounts.google.com/gsi/client';
    var APPLE_SRC = 'https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js';
    var scripts = {};
    var activeLayer = null;

    function loadScript(src) {
        if (scripts[src]) return scripts[src];
        scripts[src] = new Promise(function (resolve, reject) {
            var script = document.createElement('script');
            script.src = src;
            script.async = true;
            script.onload = function () { resolve(); };
            script.onerror = function () {
                delete scripts[src];
                reject(new Error('load failed'));
            };
            document.head.appendChild(script);
        });
        return scripts[src];
    }

    /*
     * A dimmed full-viewport layer above the Compose canvas with one card: a title, the provider's
     * button slot and Cancel. Esc, Cancel and a backdrop click all dismiss it. Colours are the app's
     * tokens (PynapliColors: Black/Surface/Border/TextPrimary/TextSecondary, ModalScrim).
     */
    function openLayer(title, onDismiss) {
        closeLayer();
        var backdrop = document.createElement('div');
        backdrop.setAttribute('role', 'dialog');
        backdrop.setAttribute('aria-modal', 'true');
        backdrop.setAttribute('aria-label', title);
        backdrop.style.cssText = 'position:fixed;inset:0;z-index:2147483000;display:flex;align-items:center;' +
            'justify-content:center;padding:16px;box-sizing:border-box;background:rgba(0,0,0,0.8);';
        var card = document.createElement('div');
        card.style.cssText = 'width:100%;max-width:360px;box-sizing:border-box;padding:24px 20px 12px;' +
            'border-radius:20px;background:#121216;border:1px solid #23232C;display:flex;flex-direction:column;' +
            'align-items:center;gap:16px;font:400 14px/1.5 system-ui,sans-serif;color:#F5F2EC;';
        var heading = document.createElement('div');
        heading.textContent = title;
        heading.style.cssText = 'font-weight:600;font-size:17px;text-align:center;';
        var slot = document.createElement('div');
        slot.style.cssText = 'min-height:44px;display:flex;align-items:center;justify-content:center;width:100%;';
        var cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.textContent = 'Cancel';
        cancel.style.cssText = 'min-height:44px;padding:0 20px;border:0;background:transparent;color:#A8A29A;' +
            'font:500 14px/1 system-ui,sans-serif;cursor:pointer;';
        card.appendChild(heading);
        card.appendChild(slot);
        card.appendChild(cancel);
        backdrop.appendChild(card);

        function onKey(event) {
            if (event.key === 'Escape') { event.preventDefault(); onDismiss(); }
        }
        cancel.addEventListener('click', function () { onDismiss(); });
        backdrop.addEventListener('click', function (event) { if (event.target === backdrop) onDismiss(); });
        document.addEventListener('keydown', onKey, true);
        document.body.appendChild(backdrop);
        activeLayer = {
            slot: slot,
            remove: function () {
                document.removeEventListener('keydown', onKey, true);
                if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
            }
        };
        return activeLayer;
    }

    function closeLayer() {
        if (activeLayer) {
            var layer = activeLayer;
            activeLayer = null;
            layer.remove();
        }
    }

    // One settle per request, and the layer always goes with it.
    function once(done) {
        var settled = false;
        return function (kind, idToken, code, givenName, message) {
            if (settled) return;
            settled = true;
            closeLayer();
            done(kind, idToken || '', code || '', givenName || '', message || '');
        };
    }

    function requestGoogle(clientId, nonce, done) {
        var finish = once(done);
        loadScript(GIS_SRC).then(function () {
            if (!window.google || !google.accounts || !google.accounts.id) {
                finish('unavailable');
                return;
            }
            google.accounts.id.initialize({
                client_id: clientId,
                nonce: nonce,
                ux_mode: 'popup',
                auto_select: false,
                context: 'use',
                itp_support: true,
                callback: function (response) {
                    if (response && response.credential) {
                        finish('success', response.credential);
                    } else {
                        finish('error', '', '', '', "Google sign-in didn't work. Try again.");
                    }
                }
            });
            var layer = openLayer('Continue with Google', function () { finish('cancelled'); });
            var width = Math.min(320, Math.max(200, layer.slot.clientWidth || 280));
            google.accounts.id.renderButton(layer.slot, {
                type: 'standard',
                theme: 'filled_black',
                size: 'large',
                text: 'continue_with',
                shape: 'pill',
                logo_alignment: 'left',
                width: width
            });
        }).catch(function () { finish('unavailable'); });
    }

    // CSRF state for Apple's round trip: 16 bytes from the browser's CSPRNG, hex encoded.
    function randomState() {
        var bytes = new Uint8Array(16);
        window.crypto.getRandomValues(bytes);
        var hex = '';
        for (var i = 0; i < bytes.length; i++) hex += ('0' + bytes[i].toString(16)).slice(-2);
        return hex;
    }

    function requestApple(servicesId, redirectUri, nonce, done) {
        var state = randomState();
        var finish = once(function (kind, idToken, code, givenName, message) {
            document.removeEventListener('AppleIDSignInOnSuccess', onSuccess);
            document.removeEventListener('AppleIDSignInOnFailure', onFailure);
            done(kind, idToken, code, givenName, message);
        });

        function handleData(data) {
            var auth = data && data.authorization;
            if (!auth || !auth.id_token || auth.state !== state) {
                finish('error', '', '', '', "Apple sign-in didn't work. Try again.");
                return;
            }
            var first = data.user && data.user.name && data.user.name.firstName;
            finish('success', auth.id_token, auth.code || '', first || '');
        }

        function handleError(error) {
            var code = error && (error.error || error);
            // user_cancelled_authorize (Cancel in Apple's sheet) and popup_closed_by_user (window closed).
            if (code === 'user_cancelled_authorize' || code === 'popup_closed_by_user') {
                finish('cancelled');
            } else {
                finish('error', '', '', '', "Apple sign-in didn't work. Try again.");
            }
        }

        function onSuccess(event) { handleData(event && event.detail && event.detail.data); }
        function onFailure(event) { handleError(event && event.detail && event.detail.error); }

        loadScript(APPLE_SRC).then(function () {
            if (!window.AppleID || !AppleID.auth) {
                finish('unavailable');
                return;
            }
            var layer = openLayer('Continue with Apple', function () { finish('cancelled'); });
            // Apple's own button, rendered by Apple JS into #appleid-signin (white on our dark surface).
            var button = document.createElement('div');
            button.id = 'appleid-signin';
            button.setAttribute('data-color', 'white');
            button.setAttribute('data-border', 'false');
            button.setAttribute('data-type', 'continue');
            button.setAttribute('data-mode', 'center-align');
            button.setAttribute('data-border-radius', '50');
            button.setAttribute('data-height', '44');
            button.setAttribute('data-width', '280');
            button.style.cssText = 'cursor:pointer;';
            layer.slot.appendChild(button);
            document.addEventListener('AppleIDSignInOnSuccess', onSuccess);
            document.addEventListener('AppleIDSignInOnFailure', onFailure);
            AppleID.auth.init({
                clientId: servicesId,
                scope: 'name email',
                redirectURI: redirectUri,
                state: state,
                nonce: nonce,
                usePopup: true
            });
            if (typeof AppleID.auth.renderButton === 'function') {
                try { AppleID.auth.renderButton(); } catch (e) { /* the fallback below covers it */ }
            }
            // If Apple's button did not render, a plain one still starts the popup from its own click.
            setTimeout(function () {
                if (activeLayer !== layer || button.childNodes.length > 0) return;
                var fallback = document.createElement('button');
                fallback.type = 'button';
                fallback.textContent = 'Continue with Apple';
                fallback.style.cssText = 'min-height:44px;width:280px;border:0;border-radius:22px;background:#FFFFFF;' +
                    'color:#000000;font:600 16px/1 -apple-system,system-ui,sans-serif;cursor:pointer;';
                fallback.addEventListener('click', function () {
                    AppleID.auth.signIn().then(handleData, handleError);
                });
                button.appendChild(fallback);
            }, 1500);
        }).catch(function () { finish('unavailable'); });
    }

    /** Signed out: Google stops auto-selecting the last account on this browser. */
    function signedOut() {
        try {
            if (window.google && google.accounts && google.accounts.id) google.accounts.id.disableAutoSelect();
        } catch (e) { /* best effort */ }
    }

    window.__pynapliSocial = {
        warmUp: function (google, apple) {
            if (google) loadScript(GIS_SRC).catch(function () {});
            if (apple) loadScript(APPLE_SRC).catch(function () {});
        },
        requestGoogle: requestGoogle,
        requestApple: requestApple,
        cancel: closeLayer,
        signedOut: signedOut
    };
})();
