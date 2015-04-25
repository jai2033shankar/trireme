/**
 * Copyright 2015 Apigee Corporation.
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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.apigee.trireme.core.internal;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Utilities for handling compiled scripts and backing off gracefully when they are too
 * large for Rhino to handle.
 */

public class ScriptUtils
{
    private static final Logger log = LoggerFactory.getLogger(ScriptUtils.class.getName());

    /** This is the message that Rhino emits if it knows that bytecode is too large */
    private static final Pattern BYTECODE_SIZE_MESSAGE =
        Pattern.compile(".*generated bytecode .+ exceeds 64K limit.*");

    /**
     * This is the maximum size, in characters, of source code that we know will generate more than 64K
     * of code. This allows us to short-circuit an expensive compilation step.
     */
    private static final int MAX_COMPILED_SCRIPT_LENGTH = 128 * 1024;

    /**
     * Try to compile the script, and return null if the script is too large.
     * However, throw if compilation fails.
     */
    public static Script tryCompile(Context cx, String code, String fileName)
    {
        if (code.length() > MAX_COMPILED_SCRIPT_LENGTH) {
            // Assume that this script won't compile -- run it later in interpreted mode.
            return null;

        } else {
            try {
                return cx.compileString(code, fileName, 1, null);

            } catch (EvaluatorException ee) {
                // Test for a script that is too large. We have to do this by checking the error message
                if (BYTECODE_SIZE_MESSAGE.matcher(ee.getMessage()).matches()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Source code for {} is too large -- running later in interpreted mode", fileName);
                    }
                    return null;
                } else {
                    throw ee;
                }
            } catch (IllegalArgumentException ie) {
                if (log.isDebugEnabled()) {
                    log.debug("Source code for {} failed compilation, possibly too large", fileName);
                }
                return null;
            }
        }
    }

    /**
     * Execute the script in interpreted mode.
     */
    public static Object interpretScript(Context cx, Scriptable scope, String code, String fileName)
    {
        if (log.isDebugEnabled()) {
            log.debug("Executing script from {} in interpreted mode because it was too large", fileName);
        }

        int oldOpt = cx.getOptimizationLevel();
        try {
            cx.setOptimizationLevel(-1);
            return cx.evaluateString(scope, code, fileName, 1, null);
        } finally {
            cx.setOptimizationLevel(oldOpt);
        }
    }
}
