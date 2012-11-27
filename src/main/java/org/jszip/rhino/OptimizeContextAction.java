/*
 * Copyright 2011-2012 Stephen Connolly.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jszip.rhino;

import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.StringUtils;
import org.jszip.pseudo.io.PseudoFile;
import org.jszip.pseudo.io.ProxyPseudoFile;
import org.jszip.pseudo.io.PseudoFileInputStream;
import org.jszip.pseudo.io.PseudoFileOutputStream;
import org.jszip.pseudo.io.PseudoFileSystem;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJavaPackage;
import org.mozilla.javascript.NativeJavaTopPackage;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.tools.shell.Global;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Stack;

/**
 * An action for running r.js against a virtual filesystem.
 */
public class OptimizeContextAction extends ScriptableObject implements ContextAction {
    private final Global global;
    private final File profileJs;
    private final String source;
    private final int lineNo;
    private final PseudoFileSystem.Layer[] layers;
    private final Log log;

    public OptimizeContextAction(Log log, Global global, File profileJs, String source, int lineNo,
                                 PseudoFileSystem.Layer... layers) {
        this.log = log;
        this.global = global;
        this.profileJs = profileJs;
        this.source = source;
        this.lineNo = lineNo;
        this.layers = layers;
    }

    public Object run(Context context) {
        context.setErrorReporter(new MavenLogErrorReporter(log));
        PseudoFileSystem fileSystem = new PseudoFileSystem(layers);
        context.putThreadLocal(OptimizeContextAction.class, log);
        fileSystem.installInContext();
        try {

            if (log.isDebugEnabled()) {
                log.debug("Virtual filesystem exposed to r.js:");
                Stack<Iterator<PseudoFile>> stack = new Stack<Iterator<PseudoFile>>();
                stack.push(Arrays.asList(fileSystem.root().listFiles()).iterator());
                while (!stack.isEmpty()) {
                    Iterator<PseudoFile> iterator = stack.pop();
                    while (iterator.hasNext()) {
                        PseudoFile f = iterator.next();
                        if (f.isFile()) {
                            log.debug("  " + f.getAbsolutePath() + " [file]");
                        } else {
                            log.debug("  " + f.getAbsolutePath() + " [dir]");
                            stack.push(iterator);
                            iterator = Arrays.asList(f.listFiles()).iterator();
                        }
                    }
                }
            }

            global.defineFunctionProperties(new String[]{"print"}, OptimizeContextAction.class,
                    ScriptableObject.DONTENUM);

            Script script = context.compileString(source, "r.js", lineNo, null);

            Scriptable argsObj = context.newArray(global,
                    new Object[]{"-o", "/build/" + profileJs.getName()});
            global.defineProperty("arguments", argsObj, ScriptableObject.DONTENUM);

            Scriptable scope = context.newObject(global);
            scope.setPrototype(global);
            scope.setParentScope(null);

            NativeJavaTopPackage $packages = (NativeJavaTopPackage) global.get("Packages");
            NativeJavaPackage $java = (NativeJavaPackage) $packages.get("java");
            NativeJavaPackage $java_io = (NativeJavaPackage) $java.get("io");

            ProxyNativeJavaPackage proxy$java = new ProxyNativeJavaPackage($java);
            ProxyNativeJavaPackage proxy$java_io = new ProxyNativeJavaPackage($java_io);
            proxy$java_io.put("File", global, get(global, "Packages." + ProxyPseudoFile.class.getName()));
            proxy$java_io.put("FileInputStream", global,
                    get(global, "Packages." + PseudoFileInputStream.class.getName()));
            proxy$java_io.put("FileOutputStream", global,
                    get(global, "Packages." + PseudoFileOutputStream.class.getName()));
            proxy$java.put("io", global, proxy$java_io);
            global.defineProperty("java", proxy$java, ScriptableObject.DONTENUM);

            if (script != null) {
                log.info("Applying r.js profile " + profileJs.getPath());
                return script.exec(context, scope);
            }
            return null;
        } finally {
            fileSystem.removeFromContext();
            context.putThreadLocal(OptimizeContextAction.class, null);
        }
    }

    private Object get(Scriptable scope, String name) {
        Scriptable cur = scope;
        for (String part : StringUtils.split(name, ".")) {
            Object next = cur.get(part, scope);
            if (next instanceof Scriptable) {
                cur = (Scriptable) next;
            } else {
                return null;
            }
        }
        return cur;
    }

    @Override
    public String getClassName() {
        return "global";
    }

    /**
     * Print the string values of its arguments.
     * <p/>
     * This method is defined as a JavaScript function. Note that its arguments
     * are of the "varargs" form, which allows it to handle an arbitrary number
     * of arguments supplied to the JavaScript function.
     */
    public static void print(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(" ");
            }

            // Convert the arbitrary JavaScript value into a string form.
            String s = Context.toString(args[i]);

            builder.append(s);
        }
        Log log = (Log) cx.getThreadLocal(OptimizeContextAction.class);
        if (log != null) {
            for (String line : builder.toString().split("(\\r\\n?)|(\\n\\r?)")) {
                log.info(line);
            }
        }
    }

}