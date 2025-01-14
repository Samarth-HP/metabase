import Cookies from "js-cookie";
import { replace } from "react-router-redux";
import { logout, refreshSession } from "metabase/auth/actions";
import { isSameOrigin } from "metabase/lib/dom";

export const SESSION_KEY = "metabase.TIMEOUT";
export const COOKIE_POOLING_TIMEOUT = 3000;
const getIsLoggedIn = () => typeof Cookies.get(SESSION_KEY) !== "undefined";

const getRedirectUrl = () => {
  const params = new URLSearchParams(window.location.search);
  const redirectUrlParam = params.get("redirect");

  return redirectUrlParam != null && isSameOrigin(redirectUrlParam)
    ? redirectUrlParam
    : null;
};

export const createSessionMiddleware = (
  resetActions = [],
  setInterval = global.setInterval,
) => {
  let intervalId;

  const sessionMiddlware = store => next => action => {
    if (intervalId == null || resetActions.includes(action.type)) {
      clearInterval(intervalId);

      let wasLoggedIn = getIsLoggedIn();

      intervalId = setInterval(async () => {
        const isLoggedIn = getIsLoggedIn();

        if (isLoggedIn !== wasLoggedIn) {
          wasLoggedIn = isLoggedIn;

          if (isLoggedIn) {
            await store.dispatch(refreshSession());
            const redirectUrl = getRedirectUrl();

            if (redirectUrl !== null) {
              store.dispatch(replace(redirectUrl));
            }
          } else {
            const url = location.pathname + location.search + location.hash;
            store.dispatch(logout(url, true));
          }
        }
      }, COOKIE_POOLING_TIMEOUT);
    }

    next(action);
  };

  return sessionMiddlware;
};
