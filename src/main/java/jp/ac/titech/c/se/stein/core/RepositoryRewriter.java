package jp.ac.titech.c.se.stein.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.Try.ThrowableFunction;

public class RepositoryRewriter extends RepositoryAccess {
    private static final Logger log = LoggerFactory.getLogger(RepositoryRewriter.class);

    private static final ObjectId ZERO = ObjectId.zeroId();

    protected final Map<ObjectId, ObjectId> commitMapping = new HashMap<>();

    protected Map<Entry, EntrySet> entryMapping = new HashMap<>();

    protected Map<RefEntry, RefEntry> refEntryMapping = new HashMap<>();

    protected ObjectInserter inserter = null;

    protected boolean pathSensitive = false;

    public void rewrite(final Context c) {
        rewriteCommits(c);
        updateRefs(c);
        cleanUp(c);
    }

    public void rewrite() {
        rewrite(new Context(null, Key.repo, writeRepo.getDirectory()));
    }

    /**
     * Sets whether entries are path-sensitive.
     */
    protected void setPathSensitive(final boolean value) {
        this.pathSensitive = value;
    }

    /**
     * Rewrites all commits.
     */
    protected void rewriteCommits(final Context c) {
        try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
            this.inserter = ins;
            try (final RevWalk walk = prepareRevisionWalk(c)) {
                for (final RevCommit commit : walk) {
                    rewriteCommit(commit, c);
                }
            }
            this.inserter = null;
        }
    }

    /**
     * Prepares the revision walk.
     */
    protected RevWalk prepareRevisionWalk(final Context c) {
        final Collection<ObjectId> starts = collectStarts(c);
        final Collection<ObjectId> uninterestings = collectUninterestings(c);

        final RevWalk walk = new RevWalk(repo);
        Try.io(() -> {
            for (final ObjectId id : starts) {
                walk.markStart(walk.parseCommit(id));
            }
            for (final ObjectId id : uninterestings) {
                walk.markUninteresting(walk.parseCommit(id));
            }
        });

        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    /**
     * Collects the set of commit Ids used as start points.
     */
    protected Collection<ObjectId> collectStarts(final Context c) {
        final List<ObjectId> result = new ArrayList<>();
        final List<Ref> refs = Try.io(() -> repo.getRefDatabase().getRefs());
        for (final Ref ref : refs) {
            if (confirmStartRef(ref, c)) {
                final ObjectId commitId = getRefTarget(ref, c);
                if (getObjectType(commitId, c) == Constants.OBJ_COMMIT) {
                    log.debug("Ref {}: added as a start point (id: {})", ref.getName(), commitId.name());
                    result.add(commitId);
                } else {
                    log.debug("Ref {}: non-commit; skipped (id: {})", ref.getName(), commitId.name());
                }
            }
        }
        return result;
    }

    /**
     * Confirms whether the given ref is used for a start point.
     */
    protected boolean confirmStartRef(final Ref ref, final Context c) {
        final String name = ref.getName();
        return name.equals(Constants.HEAD) || name.startsWith(Constants.R_HEADS) || name.startsWith(Constants.R_TAGS);
    }

    /**
     * Collects the set of commit Ids used as uninteresting points.
     */
    protected Collection<ObjectId> collectUninterestings(final Context c) {
        @SuppressWarnings("unchecked")
        final List<ObjectId> result = Collections.EMPTY_LIST;
        return result;
    }

    /**
     * Rewrites a commit.
     *
     * @param commit
     *            target commit.
     * @return the object ID of the rewritten commit
     */
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        final Context uc = c.with(Key.commit, commit);

        final ObjectId[] parentIds = rewriteParents(commit.getParents(), uc);
        final ObjectId treeId = rewriteRootTree(commit.getTree().getId(), uc);
        final PersonIdent author = rewriteAuthor(commit.getAuthorIdent(), commit, uc);
        final PersonIdent committer = rewriteCommitter(commit.getCommitterIdent(), commit, uc);
        final String message = rewriteCommitMessage(commit.getFullMessage(), commit, uc);
        final ObjectId newId = writeCommit(parentIds, treeId, author, committer, message, uc);

        final ObjectId oldId = commit.getId();
        commitMapping.put(oldId, newId);

        log.debug("Rewrite commit: {} -> {}", oldId.name(), newId.name());
        return newId;
    }

    /**
     * Rewrites the parents of a commit.
     */
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] result = new ObjectId[parents.length];
        for (int i = 0; i < parents.length; i++) {
            result[i] = commitMapping.get(parents[i]);
        }
        return result;
    }

    /**
     * Rewrites the root tree of a commit.
     */
    protected ObjectId rewriteRootTree(final ObjectId treeId, final Context c) {
        final Context uc = c.with(Key.root, treeId);

        // A root tree is represented as a special entry whose name is "/"
        final Entry root = new Entry(FileMode.TREE, "", treeId, pathSensitive ? "" : null);
        final EntrySet newRoot = getEntry(root, uc);
        final ObjectId newId = newRoot == EntrySet.EMPTY ? writeTree(EntrySet.EMPTY_ENTRIES, uc) : ((Entry) newRoot).id;

        log.debug("Rewrite tree: {} -> {}", treeId.name(), newId.name());
        return newId;
    }

    /**
     * Obtains tree entries from a tree entry.
     */
    protected EntrySet getEntry(final Entry entry, final Context c) {
        // computeIfAbsent is unsuitable because this may be invoked recursively
        final EntrySet cache = entryMapping.get(entry);
        if (cache != null) {
            return cache;
        } else {
            final EntrySet result = rewriteEntry(entry, c);
            entryMapping.put(entry, result);
            return result;
        }
    }

    /**
     * Rewrites a tree entry.
     */
    protected EntrySet rewriteEntry(final Entry entry, final Context c) {
        final Context uc = c.with(Key.path, entry.getPath());

        final ObjectId newId = entry.isTree() ? rewriteTree(entry.id, entry, c) : rewriteBlob(entry.id, entry, uc);
        final String newName = rewriteName(entry.name, entry, uc);
        return newId == ZERO ? EntrySet.EMPTY : new Entry(entry.mode, newName, newId, entry.pathContext);
    }

    /**
     * Rewrites a tree object.
     */
    protected ObjectId rewriteTree(final ObjectId treeId, final Entry entry, final Context c) {
        final Context uc = c.with(Key.path, entry.getPath());

        final List<Entry> entries = new ArrayList<>();
        String pathContext = null;
        if (pathSensitive) {
            pathContext = entry.isRoot() ? "" : entry.pathContext + "/" + entry.name;
        }
        for (final Entry e : readTree(treeId, pathContext, uc)) {
            final EntrySet rewritten = getEntry(e, c);
            rewritten.registerTo(entries);
        }
        return entries.isEmpty() ? ZERO : writeTree(entries, uc);
    }

    /**
     * Rewrites a blob object.
     */
    protected ObjectId rewriteBlob(final ObjectId blobId, final Entry entry, final Context c) {
        if (overwrite) {
            return blobId;
        } else {
            return writeBlob(readBlob(blobId, c), c);
        }
    }

    /**
     * Rewrites the name of a tree entry.
     */
    protected String rewriteName(final String name, final Entry entry, final Context c) {
        return name;
    }

    /**
     * Rewrites the author identity of a commit.
     */
    protected PersonIdent rewriteAuthor(final PersonIdent author, final RevCommit commit, final Context c) {
        return rewritePerson(author, c);
    }

    /**
     * Rewrites the committer identity of a commit.
     */
    protected PersonIdent rewriteCommitter(final PersonIdent committer, final RevCommit commit, final Context c) {
        return rewritePerson(committer, c);
    }

    /**
     * Rewrites a person identity.
     */
    protected PersonIdent rewritePerson(final PersonIdent person, final Context c) {
        return person;
    }

    /**
     * Rewrites the message of a commit.
     */
    protected String rewriteCommitMessage(final String message, final RevCommit commit, final Context c) {
        return rewriteMessage(message, commit.getId(), c);
    }

    /**
     * Rewrites a message.
     */
    protected String rewriteMessage(final String message, final ObjectId id, final Context c) {
        return "orig:" + id.name() + " " + message;
    }

    /**
     * Updates ref objects.
     */
    protected void updateRefs(final Context c) {
        final List<Ref> refs = Try.io(() -> repo.getRefDatabase().getRefs());
        for (final Ref ref : refs) {
            if (confirmUpdateRef(ref, c)) {
                updateRef(ref, c);
            }
        }
    }

    /**
     * Confirms whether the given ref is to be updated.
     */
    protected boolean confirmUpdateRef(final Ref ref, final Context c) {
        return confirmStartRef(ref, c);
    }

    /**
     * Updates a ref object.
     */
    protected RefEntry getRefEntry(final RefEntry entry, final Ref ref, final Context c) {
        final RefEntry cache = refEntryMapping.get(entry);
        if (cache != null) {
            return cache;
        } else {
            final RefEntry result = rewriteRefEntry(entry, ref, c);
            refEntryMapping.put(entry, result);
            return result;
        }
    }

    /**
     * Updates a ref object.
     */
    protected void updateRef(final Ref ref, final Context c) {
        final RefEntry oldEntry = new RefEntry(ref);
        final RefEntry newEntry = getRefEntry(oldEntry, ref, c);
        if (newEntry == RefEntry.EMPTY) {
            // delete
            if (overwrite) {
                log.debug("Delete ref: {}", oldEntry);
                applyRefDelete(oldEntry, c);
            }
            return;
        }

        if (!oldEntry.name.equals(newEntry.name)) {
            // rename
            if (overwrite) {
                log.debug("Rename ref: {} -> {}", oldEntry.name, newEntry.name);
                applyRefRename(oldEntry.name, newEntry.name, c);
            }
        }

        final boolean linkEquals = oldEntry.target == null ? newEntry.target == null : oldEntry.target.equals(newEntry.target);
        final boolean idEquals = oldEntry.id == null ? newEntry.id == null : oldEntry.id.name().equals(newEntry.id.name());

        if (!overwrite || !linkEquals || !idEquals) {
            // update
            log.debug("Update ref: {} -> {}", oldEntry, newEntry);
            applyRefUpdate(newEntry, c);
        }
    }

    /**
     * Rewrites a ref entry.
     */
    protected RefEntry rewriteRefEntry(final RefEntry entry, final Ref ref, final Context c) {
        if (entry.isSymbolic()) {
            final String newName = rewriteRefName(entry.name, ref, c);
            final String newTarget = getRefEntry(new RefEntry(ref.getTarget()), ref.getTarget(), c).name;
            return new RefEntry(newName, newTarget);
        } else {
            final String newName = rewriteRefName(entry.name, ref, c);
            final ObjectId newObjectId = rewriteRefObject(entry.id, ref, c);
            return newObjectId == ZERO ? RefEntry.EMPTY : new RefEntry(newName, newObjectId);
        }
    }

    /**
     * Rewrites the referred object by a ref.
     */
    protected ObjectId rewriteRefObject(final ObjectId id, final Ref ref, final Context c) {
        if (isTag(ref, c)) {
            return rewriteTag(id, parseTag(id, c), ref, c);
        } else {
            return rewriteReferredCommit(id, ref, c);
        }
    }

    /**
     * Rewrites the referred commit object by a ref.
     */
    protected ObjectId rewriteReferredCommit(final ObjectId id, final Ref ref, final Context c) {
        if (getObjectType(id, c) != Constants.OBJ_COMMIT) {
            // referring non-commit; ignore it
            log.debug("Ref {}: Ignore non-commit (id: {})", ref.getName(), id.name());
            return id;
        }
        final ObjectId result = commitMapping.get(id);
        if (result == null) {
            log.warn("Ref {}: Rewritten commit not found (id: {})", ref.getName(), id.name());
            return id;
        }
        return result;
    }

    /**
     * Rewrites a tag object.
     */
    protected ObjectId rewriteTag(final ObjectId tagId, final RevTag tag, final Ref ref, final Context c) {
        final ObjectId newObjectId = rewriteReferredCommit(tag.getObject(), ref, c);
        if (newObjectId == ZERO) {
            return ZERO;
        }
        log.debug("Rewrite tag target: {} -> {}", tag.getObject().name(), newObjectId.name());

        final String tagName = tag.getTagName();
        final PersonIdent tagger = rewriteTagger(tag.getTaggerIdent(), tag, ref, c);
        final String message = rewriteTagMessage(tag.getFullMessage(), tag, ref, c);
        final ObjectId newId = writeTag(newObjectId, tagName, tagger, message, c);
        log.debug("Rewrite tag: {} -> {}", tagId.name(), newId.name());
        return newId;
    }

    /**
     * Rewrites the tagger identity of a tag.
     */
    protected PersonIdent rewriteTagger(final PersonIdent tagger, final RevTag tag, final Ref ref, final Context c) {
        return rewritePerson(tagger, c);
    }

    /**
     * Rewrites the message of a tag.
     */
    protected String rewriteTagMessage(final String message, final RevTag tag, final Ref ref, final Context c) {
        return rewriteMessage(message, tag.getId(), c);
    }

    /**
     * Rewrites a ref name.
     */
    protected String rewriteRefName(final String name, final Ref ref, final Context c) {
        if (name.startsWith(Constants.R_HEADS)) {
            final String branchName = name.substring(Constants.R_HEADS.length());
            return Constants.R_HEADS + rewriteBranchName(branchName, ref, c);
        } else if (name.startsWith(Constants.R_TAGS)) {
            final String tagName = name.substring(Constants.R_TAGS.length());
            return Constants.R_TAGS + rewriteTagName(tagName, ref, c);
        } else {
            return name;
        }
    }

    /**
     * Rewrites a local branch name.
     */
    protected String rewriteBranchName(final String name, final Ref ref, final Context c) {
        return name;
    }

    /**
     * Rewrites a tag name.
     */
    protected String rewriteTagName(final String name, final Ref ref, final Context c) {
        return name;
    }

    /**
     * A hook method for cleaning up.
     */
    protected void cleanUp(final Context c) {
    }

    @Override
    protected <R> R tryInsert(final ThrowableFunction<ObjectInserter, R> f) {
        if (inserter != null) {
            return Try.io(f).apply(inserter);
        } else {
            return super.tryInsert(f);
        }
    }
}
