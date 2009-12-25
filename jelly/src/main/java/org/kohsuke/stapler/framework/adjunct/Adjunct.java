package org.kohsuke.stapler.framework.adjunct;

import org.apache.commons.jelly.JellyException;
import org.apache.commons.jelly.JellyTagException;
import org.apache.commons.jelly.Script;
import org.kohsuke.stapler.MetaClassLoader;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.WebApp;
import org.kohsuke.stapler.framework.io.IOException2;
import org.kohsuke.stapler.jelly.JellyClassLoaderTearOff;
import org.kohsuke.stapler.jelly.JellyFacet;
import org.xml.sax.SAXException;
import org.kohsuke.stapler.StaplerRequest;
import org.apache.commons.jelly.XMLOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory cache of fully inlined "adjunct" which is a pair of CSS and JavaScript.
 *
 * @author Kohsuke Kawaguchi
 */
public class Adjunct {

    public final AdjunctManager manager;

    /**
     * Fully qualified name of this adjunct that follows the dot notation.
     */
    public final String name;

    /**
     * The same as {@link #name} but uses '/' separator.
     */
    public final String slashedName;

    /**
     * List of fully qualified adjunct names that are required before this adjunct.
     */
    public final List<String> required = new ArrayList<String>();

    private final boolean hasCss;
    private final boolean hasJavaScript;

    /**
     * If the HTML that includes CSS/JavaScripts are provided
     * by this adjunct, non-null. This allows external JavaScript/CSS
     * resources to be handled in the adjunct mechanism.
     */
    private final String inclusionFragment;

    /**
     * If the adjunct includes a Jelly script, set to that script.
     * This allows a Jelly script to generate the inclusion fragment.
     * Think of this as a programmable version of the {@link #inclusionFragment}.
     */
    private final Script script;

    /**
     * Builds an adjunct.
     *
     * @param name
     *      Fully qualified name of the adjunct.
     * @param classLoader
     *      This is where adjuncts are loaded from.
     */
    public Adjunct(AdjunctManager manager, String name, final ClassLoader classLoader) throws IOException {
        this.manager = manager;
        this.name = name;
        this.slashedName = name.replace('.','/');
        this.hasCss = parseOne(classLoader, slashedName+".css");
        this.hasJavaScript = parseOne(classLoader,slashedName+".js");
        this.inclusionFragment = parseHtml(classLoader,slashedName+".html");

        URL jelly = classLoader.getResource(slashedName + ".jelly");
        if (jelly!=null) {
            try {
                script = MetaClassLoader.get(classLoader).loadTearOff(JellyClassLoaderTearOff.class).createContext().compileScript(jelly);
            } catch (JellyException e) {
                throw new IOException2("Failed to load "+jelly,e);
            }
        } else {
            script = null;
        }

        if(!hasCss && !hasJavaScript && inclusionFragment==null && script==null)
            throw new NoSuchAdjunctException("Neither "+ name +".css, .js, .html, nor .jelly were found");
    }

    /**
     * Parses CSS or JavaScript files and extract dependencies.
     */
    private boolean parseOne(ClassLoader classLoader, String resName) throws IOException {
        InputStream is = classLoader.getResourceAsStream(resName);
        if (is == null)     return false;

        BufferedReader in = new BufferedReader(new InputStreamReader(is,UTF8));
        String line;
        while((line=in.readLine())!=null) {
            Matcher m = INCLUDE.matcher(line);
            if(m.lookingAt())
                required.add(m.group(1));
        }
        in.close();
        return true;
    }

    /**
     * Parses HTML files and extract dependencies.
     */
    private String parseHtml(ClassLoader classLoader, String resName) throws IOException {
        InputStream is = classLoader.getResourceAsStream(resName);
        if (is == null)     return null;

        BufferedReader in = new BufferedReader(new InputStreamReader(is,UTF8));
        String line;
        StringBuilder buf = new StringBuilder();
        while((line=in.readLine())!=null) {
            Matcher m = HTML_INCLUDE.matcher(line);
            if(m.lookingAt())
                required.add(m.group(1));
            else
                buf.append(line).append('\n');
        }
        in.close();
        return buf.toString();
    }


    public boolean has(Kind k) {
        switch (k) {
        case CSS:   return hasCss;
        case JS:    return hasJavaScript;
        }
        throw new AssertionError(k);
    }

    public void write(StaplerRequest req, XMLOutput out) throws SAXException, IOException {
        if(inclusionFragment!=null) {
            out.write(inclusionFragment);
            return;
        }
        if (script!=null)
            try {
                WebApp.getCurrent().getFacet(JellyFacet.class).scriptInvoker.invokeScript(req, Stapler.getCurrentResponse(), script, this, out);
            } catch (JellyTagException e) {
                throw new IOException2("Failed to execute Jelly script for adjunct "+name,e);
            }
        
        if(hasCss)
            out.write("<link rel='stylesheet' href='"+req.getContextPath()+'/'+manager.rootURL+'/'+slashedName+".css' type='text/css' />");
        if(hasJavaScript)
            out.write("<script src='"+req.getContextPath()+'/'+manager.rootURL+'/'+slashedName+".js' type='text/javascript'></script>");
    }

    public enum Kind { CSS, JS }

    private static final Pattern INCLUDE = Pattern.compile("/[/*]\\s*@include (\\S+)");
    private static final Pattern HTML_INCLUDE = Pattern.compile("<@include (\\S+)>");
    private static final Charset UTF8 = Charset.forName("UTF-8");
}
