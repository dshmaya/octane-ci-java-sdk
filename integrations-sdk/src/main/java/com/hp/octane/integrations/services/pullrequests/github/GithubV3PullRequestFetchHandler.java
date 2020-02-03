
package com.hp.octane.integrations.services.pullrequests.github;

import com.hp.octane.integrations.dto.connectivity.HttpMethod;
import com.hp.octane.integrations.dto.connectivity.OctaneRequest;
import com.hp.octane.integrations.dto.connectivity.OctaneResponse;
import com.hp.octane.integrations.dto.scm.SCMRepository;
import com.hp.octane.integrations.dto.scm.SCMType;
import com.hp.octane.integrations.services.pullrequests.factory.FetchParameters;
import com.hp.octane.integrations.services.pullrequests.factory.FetchUtils;
import com.hp.octane.integrations.services.pullrequests.factory.PullRequestFetchHandler;
import com.hp.octane.integrations.services.pullrequests.github.pojo.*;
import com.hp.octane.integrations.services.pullrequests.rest.authentication.AuthenticationStrategy;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class GithubV3PullRequestFetchHandler extends PullRequestFetchHandler {

    public GithubV3PullRequestFetchHandler(AuthenticationStrategy authenticationStrategy) {
        super(authenticationStrategy);
    }

    @Override
    protected String parseRequestError(OctaneResponse response) {
        return JsonConverter.getErrorMessage(response.getBody());
    }

    @Override
    public List<com.hp.octane.integrations.dto.scm.PullRequest> fetchPullRequests(FetchParameters parameters) throws IOException {

        List<com.hp.octane.integrations.dto.scm.PullRequest> result = new ArrayList<>();
        String baseUrl = getRepoApiPath(parameters.getRepoUrl());
        parameters.getLogConsumer().accept(this.getClass().getSimpleName() + " handler, Base url : " + baseUrl);
        pingRepository(baseUrl, parameters.getLogConsumer());

        String pullRequestsUrl = baseUrl + "/pulls?state=all";
        parameters.getLogConsumer().accept("Pull requests url : " + pullRequestsUrl);
        parameters.printToLogConsumer();

        List<PullRequest> pullRequests = getPagedEntities(pullRequestsUrl, PullRequest.class, parameters.getPageSize(), parameters.getMaxPRsToFetch(), parameters.getMinUpdateTime(), false);
        List<Pattern> sourcePatterns = FetchUtils.buildPatterns(parameters.getSourceBranchFilter());
        List<Pattern> targetPatterns = FetchUtils.buildPatterns(parameters.getTargetBranchFilter());

        List<PullRequest> filteredPullRequests = pullRequests.stream()
                .filter(pr -> FetchUtils.isBranchMatch(sourcePatterns, pr.getHead().getRef()) && FetchUtils.isBranchMatch(targetPatterns, pr.getBase().getRef()))
                .collect(Collectors.toList());
        parameters.getLogConsumer().accept(String.format("Received %d pull-requests, while %d are matching source/target filters", pullRequests.size(), filteredPullRequests.size()));

        //users
        Set<String> userUrls = pullRequests.stream().map(PullRequest::getUser).map(PullRequestUser::getUrl).collect(Collectors.toSet());
        Map<String, User> login2User = new HashMap<>();
        for (String url : userUrls) {
            User user = getEntity(url, User.class);
            login2User.put(user.getLogin(), user);
        }

        for (PullRequest pr : filteredPullRequests) {
            //commits are returned in asc order by update time , therefore we need to get all before filtering , therefore page size equals to max total
            List<Commit> commits = getPagedEntities(pr.getCommitsUrl(), Commit.class, parameters.getMaxPRsToFetch(), parameters.getMaxCommitsToFetch(), parameters.getMinUpdateTime(), true);

            //commits
            List<com.hp.octane.integrations.dto.scm.SCMCommit> dtoCommits = new ArrayList<>();
            for (Commit commit : commits) {
                com.hp.octane.integrations.dto.scm.SCMCommit dtoCommit = dtoFactory.newDTO(com.hp.octane.integrations.dto.scm.SCMCommit.class)
                        .setRevId(commit.getSha())
                        .setComment(commit.getCommit().getMessage())
                        .setUser(commit.getCommit().getCommitter().getName())
                        .setUserEmail(commit.getCommit().getCommitter().getEmail())
                        .setTime(convertDateToLong(commit.getCommit().getCommitter().getDate()))
                        .setParentRevId(commit.getParents().get(0).getSha());
                dtoCommits.add(dtoCommit);
            }

            SCMRepository sourceRepository = buildScmRepository(pr.getHead());
            SCMRepository targetRepository = buildScmRepository(pr.getBase());

            User prAuthor = login2User.get(pr.getUser().getLogin());
            com.hp.octane.integrations.dto.scm.PullRequest dtoPullRequest = dtoFactory.newDTO(com.hp.octane.integrations.dto.scm.PullRequest.class)
                    .setId(Integer.toString(pr.getNumber()))
                    .setTitle(pr.getTitle())
                    .setDescription(pr.getBody())
                    .setState(pr.getState())
                    .setCreatedTime(convertDateToLong(pr.getCreatedAt()))
                    .setUpdatedTime(convertDateToLong(pr.getUpdatedAt()))
                    .setMergedTime(convertDateToLong(pr.getMergedAt()))
                    .setIsMerged(pr.getMergedAt() != null)
                    .setAuthorName(prAuthor.getName() == null ? prAuthor.getLogin() : prAuthor.getName())
                    .setAuthorEmail(prAuthor.getEmail())
                    .setClosedTime(convertDateToLong(pr.getClosedAt()))
                    .setSelfUrl(pr.getHtmlUrl())
                    .setSourceRepository(sourceRepository)
                    .setTargetRepository(targetRepository)
                    .setCommits(dtoCommits);
            result.add(dtoPullRequest);
        }
        return result;
    }

    public static Long convertDateToLong(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        //All timestamps return in ISO 8601 format:YYYY-MM-DDTHH:MM:SSZ
        return Instant.parse(dateStr).getEpochSecond() * 1000;
    }

    public static String convertLongDateToISO8601(long date) {
        //All timestamps return in ISO 8601 format:YYYY-MM-DDTHH:MM:SSZ
        return Instant.ofEpochMilli(date).toString();
    }

    private SCMRepository buildScmRepository(PullRequestRepo ref) {
        return dtoFactory.newDTO(SCMRepository.class)
                .setUrl(ref.getRepo() != null ? ref.getRepo().getClone_url() : "unknown repository")
                .setBranch(ref.getRef())
                .setType(SCMType.GIT);
    }

    /***
     *
     * @param url
     * @param entityType
     * @param pageSize
     * @param maxTotal
     * @param minUpdateTime
     * @param sortRequired - whether need to sort entities by UpdateDate, relevant for entities (like Commits) that are received from server in ascending way,
     * @param <T>
     * @return
     */
    private <T extends Entity & SupportUpdatedTime> List<T> getPagedEntities(String url, Class<T> entityType, int pageSize, int maxTotal, long minUpdateTime, boolean sortRequired) {
        try {
            List<T> result = new ArrayList<>();
            boolean finished;
            String myUrl = url + (url.contains("?") ? "" : "?") + "&per_page=" + pageSize;

            do {
                finished = true;
                OctaneRequest request = dtoFactory.newDTO(OctaneRequest.class).setUrl(myUrl).setMethod(HttpMethod.GET);
                OctaneResponse response = restClient.executeRequest(request);
                List<T> collection = JsonConverter.convertCollection(response.getBody(), entityType);
                result.addAll(collection);

                myUrl = getNextPageLink(response);
                if (myUrl != null) {
                    finished = false;
                }

                if (sortRequired) {
                    //result.forEach(p -> p.getUpdatedTime());//only populate update time
                    result.sort((Comparator<SupportUpdatedTime>) (o1, o2) -> Long.compare(o2.getUpdatedTime(), o1.getUpdatedTime()));
                }

                //remove exceeding items
                while (result.size() > maxTotal) {
                    result.remove(result.size() - 1);
                    finished = true;
                }

                //remove outdated items
                for (int i = result.size() - 1; i >= 0 && minUpdateTime > 0; i--) {
                    if (result.get(i).getUpdatedTime() <= minUpdateTime) {
                        result.remove(i);
                        finished = true;
                    } else {
                        break;
                    }
                }

            } while (!finished);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to getPagedEntities : " + e.getMessage(), e);
        }
    }

    private <T extends Entity> T getEntity(String url, Class<T> entityType) {
        try {
            OctaneRequest request = dtoFactory.newDTO(OctaneRequest.class).setUrl(url).setMethod(HttpMethod.GET);
            OctaneResponse response = restClient.executeRequest(request);
            return JsonConverter.convert(response.getBody(), entityType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to getEntity : " + e.getMessage(), e);
        }
    }

    private String getNextPageLink(OctaneResponse response) {
        String linkHeaderValue = response.getHeaders().get("Link");
        if (linkHeaderValue != null) {
            //<https://api.github.com/repositories/6774631/pulls?state=all&page=2>; rel="next", <https://api.github.com/repositories/6774631/pulls?state=all&page=10>; rel="last"
            String[] linksArr = linkHeaderValue.split(",");
            for (String link : linksArr) {
                if (link.endsWith("rel=\"next\"")) {
                    String[] segments = link.split(";");
                    if (segments.length == 2) {
                        String next = segments[0].trim();
                        if (next.startsWith("<") && next.endsWith(">")) {
                            return next.substring(1, next.length() - 1);
                        }
                    }
                }
            }
        }
        return null;
    }
}
