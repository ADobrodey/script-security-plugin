/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.sandbox.groovy;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.Extension;
import hudson.PluginManager;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.FormValidation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.codehaus.groovy.control.CompilationFailedException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import org.jenkinsci.plugins.scriptsecurity.scripts.ClasspathEntry;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.scriptsecurity.scripts.UnapprovedClasspathException;
import org.jenkinsci.plugins.scriptsecurity.scripts.UnapprovedUsageException;
import org.jenkinsci.plugins.scriptsecurity.scripts.languages.GroovyLanguage;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Convenience structure encapsulating a Groovy script that may either be approved whole or sandboxed.
 * May be kept as the value of a field and passed in a {@link DataBoundConstructor} parameter;
 * you <strong>must</strong> call {@link #configuring} or a related method from your own constructor.
 * Use {@code <f:property field="…"/>} to configure it from Jelly.
 */
public final class SecureGroovyScript extends AbstractDescribableImpl<SecureGroovyScript> {

    private final String script;
    private final boolean sandbox;
    private final @CheckForNull List<ClasspathEntry> classpath;
    private transient boolean calledConfiguring;

    @DataBoundConstructor public SecureGroovyScript(String script, boolean sandbox, @CheckForNull List<ClasspathEntry> classpath) {
        this.script = script;
        this.sandbox = sandbox;
        this.classpath = classpath;
    }

    @Deprecated public SecureGroovyScript(String script, boolean sandbox) {
        this(script, sandbox, null);
    }

    private Object readResolve() {
        configuring(ApprovalContext.create());
        return this;
    }

    public String getScript() {
        return script;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public @Nonnull List<ClasspathEntry> getClasspath() {
        return classpath != null ? classpath : Collections.<ClasspathEntry>emptyList();
    }

    /**
     * To be called in your own {@link DataBoundConstructor} when storing the field of this type.
     * @param context an approval context
     * @return this object
     */
    public SecureGroovyScript configuring(ApprovalContext context) {
        calledConfiguring = true;
        if (!sandbox) {
            ScriptApproval.get().configuring(script, GroovyLanguage.get(), context);
        }
        for (ClasspathEntry entry : getClasspath()) {
            ScriptApproval.get().configuring(entry, context);
        }
        return this;
    }

    private static @CheckForNull Item currentItem() {
        StaplerRequest req = Stapler.getCurrentRequest();
        return req != null ? req.findAncestorObject(Item.class) : null;
    }

    /** Convenience form of {@link #configuring} that calls {@link ApprovalContext#withCurrentUser} and {@link ApprovalContext#withItemAsKey}. */
    public SecureGroovyScript configuringWithKeyItem() {
        ApprovalContext context = ApprovalContext.create();
        context = context.withCurrentUser().withItemAsKey(currentItem());
        return configuring(context);
    }

    /** Convenience form of {@link #configuring} that calls {@link ApprovalContext#withCurrentUser} and {@link ApprovalContext#withItem}. */
    public SecureGroovyScript configuringWithNonKeyItem() {
        ApprovalContext context = ApprovalContext.create();
        context = context.withCurrentUser().withItem(currentItem());
        return configuring(context);
    }

    /**
     * Runs the Groovy script, using the sandbox if so configured.
     * @param loader a class loader for constructing the shell, such as {@link PluginManager#uberClassLoader} (will be augmented by {@link #getClasspath} if nonempty)
     * @param binding Groovy variable bindings
     * @return the result of evaluating script using {@link GroovyShell#evaluate(String)}
     * @throws Exception in case of a general problem
     * @throws RejectedAccessException in case of a sandbox issue
     * @throws UnapprovedUsageException in case of a non-sandbox issue
     * @throws UnapprovedClasspathException in case some unapproved classpath entries were requested
     */
    public Object evaluate(ClassLoader loader, Binding binding) throws Exception {
        if (!calledConfiguring) {
            throw new IllegalStateException("you need to call configuring or a related method before using GroovyScript");
        }
        List<ClasspathEntry> cp = getClasspath();
        if (!cp.isEmpty()) {
            List<URL> urlList = new ArrayList<URL>(cp.size());
            
            for (ClasspathEntry entry : cp) {
                ScriptApproval.get().using(entry);
                urlList.add(entry.getURL());
            }
            
            loader = new URLClassLoader(urlList.toArray(new URL[urlList.size()]), loader);
        }
        if (sandbox) {
            GroovyShell shell = new GroovyShell(loader, binding, GroovySandbox.createSecureCompilerConfiguration());
            try {
                return GroovySandbox.run(shell.parse(script), Whitelist.all());
            } catch (RejectedAccessException x) {
                throw ScriptApproval.get().accessRejected(x, ApprovalContext.create());
            }
        } else {
            return new GroovyShell(loader, binding).evaluate(ScriptApproval.get().using(script, GroovyLanguage.get()));
        }
    }

    @Extension public static final class DescriptorImpl extends Descriptor<SecureGroovyScript> {

        @Override public String getDisplayName() {
            return ""; // not intended to be displayed on its own
        }

        public FormValidation doCheckScript(@QueryParameter String value, @QueryParameter boolean sandbox) {
            try {
                new GroovyShell(Jenkins.getInstance().getPluginManager().uberClassLoader).parse(value);
            } catch (CompilationFailedException x) {
                return FormValidation.error(x.getLocalizedMessage());
            }
            return sandbox ? FormValidation.ok() : ScriptApproval.get().checking(value, GroovyLanguage.get());
        }

    }

}
