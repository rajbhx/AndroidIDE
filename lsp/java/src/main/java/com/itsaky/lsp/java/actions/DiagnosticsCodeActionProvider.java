/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.lsp.java.actions;

import static com.itsaky.androidide.utils.Logger.newInstance;
import static com.itsaky.lsp.java.utils.CodeActionUtils.extractExceptionName;
import static com.itsaky.lsp.java.utils.CodeActionUtils.extractNotThrownExceptionName;
import static com.itsaky.lsp.java.utils.CodeActionUtils.extractRange;
import static com.itsaky.lsp.java.utils.CodeActionUtils.findClassNeedingConstructor;
import static com.itsaky.lsp.java.utils.CodeActionUtils.findMethod;
import static com.itsaky.lsp.java.utils.CodeActionUtils.findPosition;
import static com.itsaky.lsp.java.utils.CodeActionUtils.unwrapJCDiagnostic;

import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.itsaky.androidide.utils.Logger;
import com.itsaky.lsp.java.compiler.CompileTask;
import com.itsaky.lsp.java.compiler.CompilerProvider;
import com.itsaky.lsp.java.compiler.SynchronizedTask;
import com.itsaky.lsp.java.rewrite.AddException;
import com.itsaky.lsp.java.rewrite.AddImport;
import com.itsaky.lsp.java.rewrite.AddSuppressWarningAnnotation;
import com.itsaky.lsp.java.rewrite.ConvertFieldToBlock;
import com.itsaky.lsp.java.rewrite.ConvertVariableToStatement;
import com.itsaky.lsp.java.rewrite.CreateMissingMethod;
import com.itsaky.lsp.java.rewrite.GenerateRecordConstructor;
import com.itsaky.lsp.java.rewrite.ImplementAbstractMethods;
import com.itsaky.lsp.java.rewrite.RemoveClass;
import com.itsaky.lsp.java.rewrite.RemoveException;
import com.itsaky.lsp.java.rewrite.RemoveMethod;
import com.itsaky.lsp.java.rewrite.Rewrite;
import com.itsaky.lsp.java.utils.MethodPtr;
import com.itsaky.lsp.models.CodeActionItem;
import com.itsaky.lsp.models.DiagnosticItem;
import com.itsaky.lsp.models.Range;
import com.sun.tools.javac.util.JCDiagnostic;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Provides code actions for diagnostics.
 *
 * @author Akash Yadav
 */
public class DiagnosticsCodeActionProvider implements ActionProvider {

    private static final Logger LOG = newInstance("JavaDiagnosticsCodeActionProvider");

    @NonNull
    @Override
    public List<CodeActionItem> provideActions(
            @NonNull CompilerProvider compiler,
            @NonNull Path file,
            @NonNull Range range,
            @NonNull List<DiagnosticItem> diagnostics) {
        LOG.info(
                String.format(
                        Locale.getDefault(),
                        "Check %d diagnostics for quick fixes...",
                        diagnostics.size()));
        Instant started = Instant.now();
        final SynchronizedTask synchronizedTask = compiler.compile(file);
        final List<Pair<String, Rewrite>> actions =
                synchronizedTask.get(task -> computeActions(compiler, file, diagnostics, task));

        final List<CodeActionItem> result = new ArrayList<>();
        for (Pair<String, Rewrite> pair : actions) {
            try {
                result.addAll(pair.second.asCodeActions(compiler, pair.first));
            } catch (Throwable throwable) {
                LOG.error("Unable to create rewrite for quickfix", throwable);
            }
        }

        LOG.debug(
                String.format(
                        Locale.ROOT,
                        "Created %d code actions in %dms",
                        result.size(),
                        Instant.now().toEpochMilli() - started.toEpochMilli()));

        return result;
    }

    @NonNull
    private List<Pair<String, Rewrite>> computeActions(
            CompilerProvider compiler,
            @NonNull Path file,
            @NonNull List<DiagnosticItem> diagnostics,
            CompileTask task) {
        List<Pair<String, Rewrite>> rewrites = new ArrayList<>();
        for (DiagnosticItem d : diagnostics) {
            List<Pair<String, Rewrite>> pairs =
                    codeActionForDiagnostic(compiler, task, file, d).stream()
                            .filter(pair -> !TextUtils.isEmpty(pair.first) && pair.second != null)
                            .collect(Collectors.toList());
            rewrites.addAll(pairs);
        }
        return rewrites;
    }

    @NonNull
    private List<Pair<String, Rewrite>> codeActionForDiagnostic(
            CompilerProvider compiler, CompileTask task, Path file, @NonNull DiagnosticItem d) {
        switch (d.getCode()) {
            case "unused_local":
                return handleUnusedLocal(task, file, d);
            case "unused_field":
                return handleUnusedField(task, file, d);
            case "unused_class":
                return handleUnusedClass(task, file, d);
            case "unused_method":
                return handleUnusedMethod(task, d);
            case "unused_throws":
                return handleUnusedThrows(task, d);
            case "compiler.warn.unchecked.call.mbr.of.raw.type":
                return handleUnchecked(task, d);
            case "compiler.err.unreported.exception.need.to.catch.or.throw":
                return handleUnreportedException(task, d);
            case "compiler.err.cant.resolve.location":
                return handleNonImportedClass(compiler, task, file, d);
            case "compiler.err.var.not.initialized.in.default.constructor":
                return handleVarNotInitialized(task, d);
            case "compiler.err.does.not.override.abstract":
                return handleDoesNotOverrideAbstract(d);
            case "compiler.err.cant.resolve.location.args":
                return handleMissingMethod(task, file, d);
            default:
                return Collections.singletonList(Pair.create("", null));
        }
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleMissingMethod(
            CompileTask task, Path file, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        rewrite = new CreateMissingMethod(file, findPosition(task, d.getRange().getStart()));
        title = "Create missing method";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleDoesNotOverrideAbstract(@NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        //noinspection unchecked
        final Diagnostic<? extends JavaFileObject> diagnostic =
                (Diagnostic<? extends JavaFileObject>) d.getExtra();
        JCDiagnostic jcDiagnostic = unwrapJCDiagnostic(diagnostic);
        if (jcDiagnostic == null) {
            return Collections.singletonList(Pair.create("", null));
        }

        rewrite = new ImplementAbstractMethods(jcDiagnostic);
        title = "Implement abstract methods";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleVarNotInitialized(
            CompileTask task, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        final String needsConstructor = findClassNeedingConstructor(task, d.getRange());
        if (needsConstructor == null) {
            return Collections.singletonList(Pair.create("", null));
        }

        rewrite = new GenerateRecordConstructor(needsConstructor);
        title = "Generate constructor";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleNonImportedClass(
            @NonNull CompilerProvider compiler,
            CompileTask task,
            Path file,
            @NonNull DiagnosticItem d) {
        CharSequence simpleName = extractRange(task, d.getRange());
        List<Pair<String, Rewrite>> allImports = new ArrayList<>();
        final List<String> classes = compiler.publicTopLevelTypes();

        for (int i = 0; i < classes.size(); i++) {
            String klass = classes.get(i);
            if (klass.contains("/")) {
                klass = klass.replace("/", ".");
            }

            if (!klass.endsWith("." + simpleName)) {
                continue;
            }

            String actionTitle = "Import '" + klass + "'";
            final Rewrite addImport = new AddImport(file, klass);
            allImports.add(Pair.create(actionTitle, addImport));
        }

        return allImports;
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnreportedException(
            CompileTask task, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        final MethodPtr needsThrow = findMethod(task, d.getRange());
        final String exceptionName = extractExceptionName(d.getMessage());
        rewrite =
                new AddException(
                        needsThrow.className,
                        needsThrow.methodName,
                        needsThrow.erasedParameterTypes,
                        exceptionName);
        title = "Add 'throws'";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnchecked(
            CompileTask task, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        final MethodPtr warnedMethod = findMethod(task, d.getRange());
        rewrite =
                new AddSuppressWarningAnnotation(
                        warnedMethod.className,
                        warnedMethod.methodName,
                        warnedMethod.erasedParameterTypes);
        title = "Suppress 'unchecked' warning";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnusedThrows(
            CompileTask task, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        final CharSequence shortExceptionName = extractRange(task, d.getRange());
        final String notThrown = extractNotThrownExceptionName(d.getMessage());
        final MethodPtr methodWithExtraThrow = findMethod(task, d.getRange());
        rewrite =
                new RemoveException(
                        methodWithExtraThrow.className,
                        methodWithExtraThrow.methodName,
                        methodWithExtraThrow.erasedParameterTypes,
                        notThrown);
        title = "Remove '" + shortExceptionName + "'";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnusedMethod(
            CompileTask task, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        final MethodPtr unusedMethod = findMethod(task, d.getRange());
        rewrite =
                new RemoveMethod(
                        unusedMethod.className,
                        unusedMethod.methodName,
                        unusedMethod.erasedParameterTypes);
        title = "Remove method";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnusedClass(
            CompileTask task, Path file, @NonNull DiagnosticItem d) {
        Rewrite rewrite;
        String title;
        rewrite = new RemoveClass(file, findPosition(task, d.getRange().getStart()));
        title = "Remove class";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnusedField(
            CompileTask task, Path file, @NonNull DiagnosticItem d) {
        Rewrite rewrite =
                new ConvertFieldToBlock(file, findPosition(task, d.getRange().getStart()));
        String title = "Convert to block";
        return Collections.singletonList(Pair.create(title, rewrite));
    }

    @NonNull
    private List<Pair<String, Rewrite>> handleUnusedLocal(
            CompileTask task, Path file, @NonNull DiagnosticItem d) {
        Rewrite rewrite =
                new ConvertVariableToStatement(file, findPosition(task, d.getRange().getStart()));
        String title = "Convert to statement";
        return Collections.singletonList(Pair.create(title, rewrite));
    }
}
