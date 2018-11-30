package jp.ac.titech.c.se.stein.sample;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.ConcurrentRepositoryRewriter;
import jp.ac.titech.c.se.stein.Try;

public class SvnMetadataAnnotator extends ConcurrentRepositoryRewriter {
    private Map<ObjectId, Integer> mapping;

    @Override
    public void addOptions(final Options opts) {
        super.addOptions(opts);
        opts.addOption(null, "svn-mapping", true, "specify svn mapping (log-git-repository)");
        opts.addOption(null, "object-mapping", true, "specify object mapping (marks-git-repository)");
    }

    @Override
    public void configure(final CommandLine cmd) {
        super.configure(cmd);
        final Path svnMappingFile = Paths.get(cmd.getOptionValue("svn-mapping"));
        final Path objectMappingFile = Paths.get(cmd.getOptionValue("object-mapping"));
        mapping = Try.io(() -> collectCommitMapping(svnMappingFile, objectMappingFile));
    }

    @Override
    protected String rewriteCommitMessage(final String message, final RevCommit commit) {
        final Integer svnId = mapping.get(commit.getId());
        if (svnId != null) {
            return "svn:r" + svnId + " " + message;
        } else {
            return message;
        }
    }

    protected Map<ObjectId, Integer> collectCommitMapping(final Path svnMappingFile, final Path objectMappingFile) throws IOException {
        final Map<String, String> svnMapping = collectSvnMapping(svnMappingFile);
        final Map<String, String> objectMapping = collectObjectMapping(objectMappingFile);
        final Map<ObjectId, Integer> result = new HashMap<>();
        for (final Map.Entry<String, String> e : svnMapping.entrySet()) {
            final ObjectId gitId = ObjectId.fromString(objectMapping.get(e.getValue()));
            final Integer svnId = Integer.valueOf(objectMapping.get(e.getValue()));
            result.put(gitId, svnId);
        }
        return result;
    }

    protected Map<String, String> collectSvnMapping(final Path file) throws IOException {
        final Pattern p = Pattern.compile("^progress SVN r(\\d+) branch master = :(\\d+)");
        try (final Stream<String> stream = Files.lines(file)) {
            return stream
                    .map(l -> p.matcher(l))
                    .filter(m -> m.matches())
                    .collect(Collectors.toMap(m -> m.group(1), m -> m.group(2)));
        }
    }

    protected Map<String, String> collectObjectMapping(final Path file) throws IOException {
        final Pattern p = Pattern.compile("^:(\\d+) (\\w+)");
        try (final Stream<String> stream = Files.lines(file)) {
            return stream
                    .map(l -> p.matcher(l))
                    .filter(m -> m.matches())
                    .collect(Collectors.toMap(m -> m.group(1), m -> m.group(2)));
        }
    }

    public static void main(final String[] args) {
        new CLI(SvnMetadataAnnotator.class, args).run();
    }
}