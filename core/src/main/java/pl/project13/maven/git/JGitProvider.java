/*
 * This file is part of git-commit-id-plugin by Konrad 'ktoso' Malawski <konrad.malawski@java.pl>
 *
 * git-commit-id-plugin is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * git-commit-id-plugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with git-commit-id-plugin.  If not, see <http://www.gnu.org/licenses/>.
 */

package pl.project13.maven.git;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.jetbrains.annotations.NotNull;

import pl.project13.jgit.DescribeCommand;
import pl.project13.jgit.DescribeResult;
import pl.project13.jgit.JGitCommon;
import pl.project13.maven.git.log.LoggerBridge;
import pl.project13.git.api.GitException;
import pl.project13.git.impl.AbstractBaseGitProvider;

public class JGitProvider extends AbstractBaseGitProvider<JGitProvider> {

  private File dotGitDirectory;
  private Repository git;
  private ObjectReader objectReader;
  private RevWalk revWalk;
  private RevCommit headCommit;
  private JGitCommon jGitCommon;

  @NotNull
  public static JGitProvider on(@NotNull File dotGitDirectory, @NotNull LoggerBridge log) {
    return new JGitProvider(dotGitDirectory, log);
  }

  JGitProvider(@NotNull File dotGitDirectory, @NotNull LoggerBridge log) {
    super(log);
    this.dotGitDirectory = dotGitDirectory;
    this.jGitCommon = new JGitCommon(log);
  }

  @Override
  public void init() throws GitException {
    git = getGitRepository();
    objectReader = git.newObjectReader();
  }

  @Override
  public String getBuildAuthorName() throws GitException {
    String userName = git.getConfig().getString("user", null, "name");
    return MoreObjects.firstNonNull(userName, "");
  }

  @Override
  public String getBuildAuthorEmail() throws GitException {
    String userEmail = git.getConfig().getString("user", null, "email");
    return MoreObjects.firstNonNull(userEmail, "");
  }

  @Override
  public void prepareGitToExtractMoreDetailedReproInformation() throws GitException {
    try {
      // more details parsed out bellow
      Ref head = git.findRef(Constants.HEAD);
      if (head == null) {
        throw new GitException("Could not get HEAD Ref, are you sure you have set the dotGitDirectory property of this plugin to a valid path?");
      }
      revWalk = new RevWalk(git);
      ObjectId headObjectId = head.getObjectId();
      if (headObjectId == null) {
        throw new GitException("Could not get HEAD Ref, are you sure you have some commits in the dotGitDirectory?");
      }
      headCommit = revWalk.parseCommit(headObjectId);
      revWalk.markStart(headCommit);
    } catch (Exception e) {
      throw new GitException("Error", e);
    }
  }

  @Override
  public String getBranchName() throws GitException {
    try {
      return git.getBranch();
    } catch (IOException e) {
      throw new GitException(e);
    }
  }

  @Override
  public String getGitDescribe() throws GitException {
    return getGitDescribe(git);
  }

  @Override
  public String getCommitId() throws GitException {
    return headCommit.getName();
  }

  @Override
  public String getAbbrevCommitId() throws GitException {
    return getAbbrevCommitId(objectReader, headCommit, abbrevLength);
  }

  @Override
  public boolean isDirty() throws GitException {
    try {
      return JGitCommon.isRepositoryInDirtyState(git);
    } catch (GitAPIException e) {
      throw new GitException("Failed to get git status: " + e.getMessage(), e);
    }
  }

  @Override
  public String getCommitAuthorName() throws GitException {
    return headCommit.getAuthorIdent().getName();
  }

  @Override
  public String getCommitAuthorEmail() throws GitException {
    return headCommit.getAuthorIdent().getEmailAddress();
  }

  @Override
  public String getCommitMessageFull() throws GitException {
    return headCommit.getFullMessage().trim();
  }

  @Override
  public String getCommitMessageShort() throws GitException {
    return headCommit.getShortMessage().trim();
  }

  @Override
  public String getCommitTime() throws GitException {
    long timeSinceEpoch = headCommit.getCommitTime();
    Date commitDate = new Date(timeSinceEpoch * 1000); // git is "by sec" and java is "by ms"
    SimpleDateFormat smf = getSimpleDateFormatWithTimeZone();
    return smf.format(commitDate);
  }

  @Override
  public String getRemoteOriginUrl() throws GitException {
    String url = git.getConfig().getString("remote", "origin", "url");
    return UriUserInfoRemover.stripCredentialsFromOriginUrl(url);
  }

  @Override
  public String getTags() throws GitException {
    try {
      Repository repo = getGitRepository();
      ObjectId headId = headCommit.toObjectId();
      Collection<String> tags = jGitCommon.getTags(repo,headId);
      return Joiner.on(",").join(tags);
    } catch (GitAPIException e) {
      log.error("Unable to extract tags from commit: {} ({})", headCommit.getName(), e.getClass().getName());
      return "";
    }
  }

  @Override
  public String getClosestTagName() throws GitException {
    Repository repo = getGitRepository();
    try {
      return jGitCommon.getClosestTagName(repo);
    } catch (Throwable t) {
      // could not find any tags to describe
    }
    return "";
  }

  @Override
  public String getClosestTagCommitCount() throws GitException {
    Repository repo = getGitRepository();
    try {
      return jGitCommon.getClosestTagCommitCount(repo, headCommit);
    } catch (Throwable t) {
      // could not find any tags to describe
    }
    return "";
  }

  @Override
  public void finalCleanUp() {
    if (revWalk != null) {
      revWalk.dispose();
    }
    // http://www.programcreek.com/java-api-examples/index.php?api=org.eclipse.jgit.storage.file.WindowCacheConfig
    // Example 3
    if (git != null) {
      git.close();
      // git.close() is not enough with jGit on Windows
      // remove the references from packFile by initializing cache used in the repository
      // fixing lock issues on Windows when repository has pack files
      WindowCacheConfig config = new WindowCacheConfig();
      config.install();
    }
  }

  @VisibleForTesting String getGitDescribe(@NotNull Repository repository) throws GitException {
    try {
      DescribeResult describeResult = DescribeCommand
          .on(repository, log)
          .apply(super.gitDescribe)
          .call();

      return describeResult.toString();
    } catch (GitAPIException ex) {
      ex.printStackTrace();
      throw new GitException("Unable to obtain git.commit.id.describe information", ex);
    }
  }

  private String getAbbrevCommitId(ObjectReader objectReader, RevCommit headCommit, int abbrevLength) throws GitException {
    try {
      AbbreviatedObjectId abbreviatedObjectId = objectReader.abbreviate(headCommit, abbrevLength);
      return abbreviatedObjectId.name();
    } catch (IOException e) {
      throw new GitException("Unable to abbreviate commit id! " +
                                         "You may want to investigate the <abbrevLength/> element in your configuration.", e);
    }
  }

  @NotNull
  private Repository getGitRepository() throws GitException {
    Repository repository;

    FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
    try {
      repository = repositoryBuilder
        .setGitDir(dotGitDirectory)
        .readEnvironment() // scan environment GIT_* variables
        .findGitDir() // scan up the file system tree
        .build();
    } catch (IOException e) {
      throw new GitException("Could not initialize repository...", e);
    }

    if (repository == null) {
      throw new GitException("Could not create git repository. Are you sure '" + dotGitDirectory + "' is the valid Git root for your project?");
    }

    return repository;
  }

  // SETTERS FOR TESTS ----------------------------------------------------

  @VisibleForTesting
  public void setRepository(Repository git) {
    this.git = git;
  }
}