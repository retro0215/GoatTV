package android.util;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

/**
 * Test-only stand-in for {@code android.util.Xml}: {@link #newPullParser()} returns kXML2's parser, the
 * implementation the framework method returns on devices. Test classes load before android.jar's stubs,
 * so XmltvParser runs for real in JVM unit tests. Never packaged into the app. See JsonReader here.
 */
public final class Xml {
    private Xml() {}

    public static XmlPullParser newPullParser() {
        return new KXmlParser();
    }
}
