package com.kylenicholls.stash.parameterizedbuilds;

import com.atlassian.bitbucket.branch.automerge.AutomaticMergeEvent;
import com.atlassian.bitbucket.content.AbstractChangeCallback;
import com.atlassian.bitbucket.content.Change;
import com.atlassian.bitbucket.content.ChangeContext;
import com.atlassian.bitbucket.content.ChangeSummary;
import com.atlassian.bitbucket.event.pull.*;
import com.atlassian.bitbucket.pull.PullRequest;
import com.atlassian.bitbucket.pull.PullRequestChangesRequest;
import com.atlassian.bitbucket.pull.PullRequestParticipant;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Branch;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.event.api.EventListener;
import com.kylenicholls.stash.parameterizedbuilds.ciserver.Jenkins;
import com.kylenicholls.stash.parameterizedbuilds.helper.SettingsService;
import com.kylenicholls.stash.parameterizedbuilds.item.BitbucketVariables;
import com.kylenicholls.stash.parameterizedbuilds.item.Job;
import com.kylenicholls.stash.parameterizedbuilds.item.Job.Trigger;
import com.kylenicholls.stash.parameterizedbuilds.item.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

//add log4j

public class PullRequestHook {
	private static final Logger log = LoggerFactory.getLogger(PullRequestHook.class);
	private final SettingsService settingsService;
	private final PullRequestService pullRequestService;
	private final Jenkins jenkins;
	private final ApplicationPropertiesService applicationPropertiesService;

	public PullRequestHook(SettingsService settingsService, PullRequestService pullRequestService,
			Jenkins jenkins, ApplicationPropertiesService applicationPropertiesService) {
		this.settingsService = settingsService;
		this.pullRequestService = pullRequestService;
		this.jenkins = jenkins;
		this.applicationPropertiesService = applicationPropertiesService;

	}

	@EventListener
	public void onPullRequestOpened(PullRequestOpenedEvent event) throws IOException {
		PullRequest pullRequest = event.getPullRequest();
		triggerFromPR(pullRequest, Trigger.PULLREQUEST);
	}

	@EventListener
	public void onPullRequestReOpened(PullRequestReopenedEvent event) throws IOException {
		PullRequest pullRequest = event.getPullRequest();
		triggerFromPR(pullRequest, Trigger.PULLREQUEST);
	}

	@EventListener
	public void onPullRequestRescoped(PullRequestRescopedEvent event) throws IOException {
		PullRequest pullRequest = event.getPullRequest();
		// Rescoped event is triggered if the source OR destination branch is
		// updated. We only want to trigger builds if the source commit hash
		// changes
		if (!event.getPreviousFromHash().equals(pullRequest.getFromRef().getLatestCommit())) {
			triggerFromPR(pullRequest, Trigger.PULLREQUEST);
		}
	}

	@EventListener
	public void onPullRequestMerged(PullRequestMergedEvent event) throws IOException {
		PullRequest pullRequest = event.getPullRequest();
		triggerFromPR(pullRequest, Trigger.PRMERGED);
	}

	@EventListener
	public void onPullRequestAutomaticMerged(AutomaticMergeEvent event) throws IOException {
		Iterable<Branch> branches = event.getMergePath();
		for (Branch branch : branches){
			triggerFromPR(branch, event, Trigger.PRAUTOMERGED);
		}
	}

	@EventListener
	public void onPullRequestDeclined(PullRequestDeclinedEvent event) throws IOException {
		PullRequest pullRequest = event.getPullRequest();
		triggerFromPR(pullRequest, Trigger.PRDECLINED);


	}

	private void triggerFromPR(Branch branch, AutomaticMergeEvent event, Trigger trigger){
		Repository repository = event.getRepository();
		if (!settingsService.getHook(repository).isEnabled()) {
			return;
		}
		ApplicationUser user = event.getUser();

		String projectKey = repository.getProject().getKey();
		String commit = branch.getLatestCommit();
		String branch_name = branch.getDisplayId();
		String url = applicationPropertiesService.getBaseUrl().toString();
		BitbucketVariables.Builder builder = new BitbucketVariables.Builder().branch(branch_name)
				.commit(commit).url(url)
				.repoName(repository.getSlug())
				.projectName(projectKey);

		BitbucketVariables bitbucketVariables = builder.build();
		triggerJenkinsJobs(bitbucketVariables, repository, trigger, projectKey, user, null);
	}

	private void triggerFromPR(PullRequest pullRequest, Trigger trigger) throws IOException {
		Repository repository = pullRequest.getFromRef().getRepository();
		if (!settingsService.getHook(repository).isEnabled()) {
			return;
		}
		ApplicationUser user = pullRequest.getAuthor().getUser();
		String projectKey = repository.getProject().getKey();
		String branch = pullRequest.getFromRef().getDisplayId();
		String commit = pullRequest.getFromRef().getLatestCommit();
		String url = applicationPropertiesService.getBaseUrl().toString();
		long prId = pullRequest.getId();
		String prAuthor = pullRequest.getAuthor().getUser().getDisplayName();
        String prAuthorEmail =pullRequest.getAuthor().getUser().getEmailAddress(); //add by Rock



		//add by Rock, if has reviewers
		Iterator<PullRequestParticipant> reviewers = pullRequest.getReviewers().iterator(); // see http://bbs.csdn.net/topics/391872997 dead cycle issue
		while (reviewers.hasNext()) {
			PullRequestParticipant participant = reviewers.next();
			ApplicationUser reviewer = participant.getUser();
			if (reviewer != null) {
				prAuthorEmail = prAuthorEmail + ", " + reviewer.getEmailAddress();
			}

		}
		log.info("prAuthorEmail = " +prAuthorEmail);


		String prTitle = pullRequest.getTitle();
		String prDescription = pullRequest.getDescription();
		String prDest = pullRequest.getToRef().getDisplayId();
		String prUrl = url + "/projects/" + projectKey + "/repos/" + repository.getSlug() + "/pull-requests/" + prId;

		BitbucketVariables.Builder builder = new BitbucketVariables.Builder().branch(branch)
				.commit(commit).url(url).prId(prId).prAuthor(prAuthor).prTitle(prTitle)
				.prDestination(prDest).prUrl(prUrl)
				.repoName(repository.getSlug())
				.projectName(projectKey)
                .prAuthorEmail(prAuthorEmail); //add by Rock

		if (prDescription != null) {
			builder.prDescription(prDescription);
		}
		BitbucketVariables bitbucketVariables = builder.build();
		triggerJenkinsJobs(bitbucketVariables, repository, trigger, projectKey, user, pullRequest);
	}

	private void triggerJenkinsJobs(BitbucketVariables bitbucketVariables, Repository repository, Trigger trigger, String projectKey, ApplicationUser user, PullRequest pullRequest){
		Settings settings = settingsService.getSettings(repository);
		if (settings == null) {
			return;
		}

		for (final Job job : settingsService.getJobs(settings.asMap())) {
			List<Trigger> triggers = job.getTriggers();
			final String pathRegex = job.getPathRegex();

			if (triggers.contains(trigger)) {
				Server jenkinsServer = jenkins.getJenkinsServer(projectKey);
				String joinedUserToken = jenkins.getJoinedUserToken(user, projectKey);
				if (jenkinsServer == null) {
					jenkinsServer = jenkins.getJenkinsServer();
					joinedUserToken = jenkins.getJoinedUserToken(user);
				}

				final String buildUrl = job
						.buildUrl(jenkinsServer, bitbucketVariables, joinedUserToken != null);
				log.info("Jenkins get alternative url " + jenkinsServer.getAltUrl());
				log.info("Jenkins request url  " + buildUrl);

				// use default user and token if the user that triggered the
				// build does not have a token set
				boolean prompt = false;
				if (joinedUserToken == null) {
					prompt = true;
					if (!jenkinsServer.getUser().isEmpty()) {
						joinedUserToken = jenkinsServer.getJoinedToken();
					}
				}

				final String token = joinedUserToken;
				final boolean finalPrompt = prompt;

				if (pathRegex.trim().isEmpty()) {
					jenkins.triggerJob(buildUrl, token, finalPrompt);
				} else if (pullRequest != null){
					pullRequestService
							.streamChanges(new PullRequestChangesRequest.Builder(pullRequest)
									.build(), new AbstractChangeCallback() {
										@Override
										public boolean onChange(Change change) throws IOException {
											String changedFile = change.getPath().toString();
											if (changedFile.matches(pathRegex)) {
												jenkins.triggerJob(buildUrl, token, finalPrompt);
												return false;
											}
											return true;
										}

										@Override
										public void onEnd(ChangeSummary summary)
												throws IOException {
											// noop
										}

										@Override
										public void onStart(ChangeContext context)
												throws IOException {
											// noop
										}
									});
				}
			}
		}
	}
}
