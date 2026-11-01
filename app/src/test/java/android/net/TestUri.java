package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;

/**
 * Minimal non-null Uri for JVM unit tests. The Android SDK stubs return null from
 * Uri.parse/Uri.EMPTY, which prevents constructing a DataSpec in local tests.
 */
public class TestUri extends Uri {
    public TestUri() {
        super();
    }

    @Override
    public Builder buildUpon() {
        return new Builder();
    }

    @Override
    public String getAuthority() {
        return "test";
    }

    @Override
    public String getEncodedAuthority() {
        return "test";
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public String getEncodedPath() {
        return "/media";
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return "//test/media";
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getHost() {
        return "test";
    }

    @Override
    public String getLastPathSegment() {
        return "media";
    }

    @Override
    public String getPath() {
        return "/media";
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.singletonList("media");
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getScheme() {
        return "https";
    }

    @Override
    public String getSchemeSpecificPart() {
        return "//test/media";
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String toString() {
        return "https://test/media";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(toString());
    }
}
