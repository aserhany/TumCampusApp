package de.tum.in.tumcampusapp.tumonline;

import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;

import com.google.common.base.Optional;
import com.google.common.net.UrlEscapers;

import org.simpleframework.xml.core.Persister;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import de.tum.in.tumcampusapp.R;
import de.tum.in.tumcampusapp.auxiliary.Const;
import de.tum.in.tumcampusapp.auxiliary.NetUtils;
import de.tum.in.tumcampusapp.auxiliary.Utils;
import de.tum.in.tumcampusapp.models.managers.CacheManager;
import de.tum.in.tumcampusapp.models.managers.TumManager;

/**
 * This class will handle all action needed to communicate with the TUMOnline
 * XML-RPC backend. ALl communications is based on the base-url which is
 * attached by the Token and additional parameters.
 */
public final class TUMOnlineRequest<T> {
    // server address
    private static final String SERVICE_BASE_URL = "https://campus.tum.de/tumonline/wbservicesbasic.";
    //private static final String SERVICE_BASE_URL = "https://campusquality.tum.de/QSYSTEM_TUM/wbservicesbasic.";

    /**
     * String possibly contained in response from server
     */
    private static final String NO_FUNCTION_RIGHTS = "Keine Rechte für Funktion";

    /**
     * String possibly contained in response from server
     */
    private static final String TOKEN_NOT_CONFIRMED = "Token ist nicht bestätigt oder ungültig!";
    /**
     * NetUtils instance for fetching
     */
    private final NetUtils net;
    private final CacheManager cacheManager;
    private final TumManager tumManager;
    /**
     * Context
     */
    private final Context mContext;
    // force to fetch data and fill cache
    private boolean fillCache;
    // set to null, if not needed
    private String accessToken;
    /**
     * asynchronous task for interactive fetch
     */
    private AsyncTask<Void, Void, Optional<T>> backgroundTask;
    /**
     * method to call
     */
    private TUMOnlineConst<T> method;
    /**
     * a list/map for the needed parameters
     */
    private Map<String, String> parameters;
    private String lastError = "";

    private TUMOnlineRequest(Context context) {
        mContext = context;

        cacheManager = new CacheManager(context);
        tumManager = new TumManager(context);
        net = new NetUtils(context);

        resetParameters();
    }

    public TUMOnlineRequest(TUMOnlineConst<T> method, Context context, boolean needsToken) {
        this(context);
        this.method = method;

        if (needsToken) {
            this.loadAccessTokenFromPreferences(context);
        }
    }

    public TUMOnlineRequest(TUMOnlineConst<T> method, Context context) {
        this(method, context, true);
        this.fillCache = true;
    }

    public void cancelRequest(boolean mayInterruptIfRunning) {
        // Cancel background task just if one has been established
        if (backgroundTask != null) {
            backgroundTask.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * Fetches the result of the HTTPRequest (which can be seen by using {@link #getRequestURL()})
     *
     * @return output will be a raw String
     */
    public Optional<T> fetch() {
        // set parameter on the TUMOnline request an fetch the results
        String url = this.getRequestURL();

        //Check for error lock
        String error = this.tumManager.checkLock(url);
        if (error != null) {
            Utils.log("aborting fetch URL (" + error + ") " + url);
            lastError = error;
            return Optional.absent();
        }

        Utils.log("fetching URL " + url);

        Optional<String> result;
        try {
            result = cacheManager.getFromCache(url);
            if (NetUtils.isConnected(mContext) && (!result.isPresent() || fillCache)) {
                result = net.downloadStringHttp(url);
            }
        } catch (IOException e) {
            Utils.log(e, "FetchError");
            lastError = e.getMessage();
            result = Optional.absent();
        }

        T res = null;
        if (result.isPresent()) {
            try {
                res = new Persister().read(method.getResponse(), result.get());
                cacheManager.addToCache(url, result.get(), method.getValidity(), CacheManager.CACHE_TYP_DATA);
                Utils.logv("added to cache " + url);

                //Release any lock present in the database
                tumManager.releaseLock(url);
            } catch (Exception e) {
                //Serialisation failed - lock for a specific time, save the error message
                lastError = tumManager.addLock(url, result.get());
            }
        }

        return Optional.fromNullable(res);
    }

    /**
     * this fetch method will fetch the data from the TUMOnline Request and will
     * address the listeners onFetch if the fetch succeeded, else the
     * onFetchError will be called
     *
     * @param context  the current context (may provide the current activity)
     * @param listener the listener, which takes the result
     */
    public void fetchInteractive(final Context context, final TUMOnlineRequestFetchListener<T> listener) {

        if (!loadAccessTokenFromPreferences(context)) {
            listener.onFetchCancelled();
        }

        // fetch information in a background task and show progress dialog in
        // meantime
        backgroundTask = new AsyncTask<Void, Void, Optional<T>>() {

            @Override
            protected Optional<T> doInBackground(Void... params) {
                // we are online, return fetch result
                return fetch();
            }

            @Override
            protected void onPostExecute(Optional<T> result) {
                if (result.isPresent()) {
                    Utils.logv("Received result <" + result + '>');
                } else {
                    Utils.log("No result available");
                }

                // Handles result
                if (!NetUtils.isConnected(mContext)) {
                    if (result.isPresent()) {
                        Utils.showToast(mContext, R.string.no_internet_connection);
                    } else {
                        listener.onNoInternetError();
                        return;
                    }
                }

                //Check for common errors
                if (!result.isPresent()) {
                    String error;
                    if (lastError.contains(TOKEN_NOT_CONFIRMED)) {
                        error = context.getString(R.string.dialog_access_token_invalid);
                    } else if (lastError.contains(NO_FUNCTION_RIGHTS)) {
                        error = context.getString(R.string.dialog_no_rights_function);
                    } else if (lastError.isEmpty()) {
                        error = context.getString(R.string.empty_result);
                    } else {
                        error = lastError;
                    }
                    listener.onFetchError(error);
                    return;
                }

                //Release any lock present in the database
                tumManager.releaseLock(TUMOnlineRequest.this.getRequestURL());

                // If there could not be found any problems return usual on Fetch method
                listener.onFetch(result.get());
            }

        }.execute();
    }

    /**
     * This will return the URL to the TUMOnlineRequest with regard to the set parameters
     *
     * @return a String URL
     */
    public String getRequestURL() {
        StringBuilder url = new StringBuilder(SERVICE_BASE_URL).append(method).append('?');

        // Builds to be fetched URL based on the base-url and additional parameters
        for (Entry<String, String> pairs : parameters.entrySet()) {
            url.append(pairs.getKey()).append('=').append(pairs.getValue()).append('&');
        }
        return url.toString();
    }

    /**
     * Check if TUMOnline access token can be retrieved from shared preferences.
     *
     * @param context The context
     * @return true if access token is available; false otherwise
     */
    private boolean loadAccessTokenFromPreferences(Context context) {
        accessToken = PreferenceManager.getDefaultSharedPreferences(context).getString(Const.ACCESS_TOKEN, null);

        // no access token set, or it is obviously wrong
        if (accessToken == null || accessToken.length() < 1) {
            return false;
        }

        // ok, access token seems valid (at first)
        setParameter(Const.P_TOKEN, accessToken);
        return true;
    }

    /**
     * Reset parameters to an empty Map
     */
    void resetParameters() {
        parameters = new HashMap<>();
        // set accessToken as parameter if available
        if (accessToken != null) {
            parameters.put(Const.P_TOKEN, accessToken);
        }
    }

    /**
     * Sets one parameter name to its given value
     *
     * @param name  identifier of the parameter
     * @param value value of the parameter
     */
    public void setParameter(String name, String value) {
        parameters.put(name, UrlEscapers.urlPathSegmentEscaper().escape(value));
    }

    public void setParameterEncoded(String name, String value) {
        parameters.put(name, Uri.encode(value));
    }

    public void setForce(boolean force) {
        fillCache = force;
    }

    public String getLastError() {
        return this.lastError;
    }
}
