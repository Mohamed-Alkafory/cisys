/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown articles. Anyone may read them; who may write them is a general setting (see {@link
 * ProjectAccess#canEditArticles()}).
 */
@Path("/api/articles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ArticleResource {
  @Inject ProjectAccess access;
  @Inject UserDirectory directory;

  /** The title and markdown body of an article. */
  public record Input(String title, String body) {}

  /** An article in the list. */
  public record Summary(Long id, String title, String author, Instant updatedAt) {}

  /** The articles, and whether the caller may write them. */
  public record Listing(boolean canEdit, List<Summary> articles) {}

  /** One article. */
  public record View(
      Long id,
      String title,
      String body,
      String author,
      Instant createdAt,
      Instant updatedAt,
      boolean canEdit) {}

  /** Every article, most recently changed first. */
  @GET
  @PermitAll
  @PublicRateLimited
  public Listing list() {
    Map<String, String> names = new HashMap<>();
    List<Summary> articles =
        Article.recent().stream()
            .map(a ->
                new Summary(
                    a.id,
                    a.title,
                    names.computeIfAbsent(a.authorSubject, this::username),
                    a.updatedAt))
            .toList();
    return new Listing(access.canEditArticles(), articles);
  }

  /** One article. */
  @GET
  @Path("/{id}")
  @PermitAll
  @PublicRateLimited
  public View get(@PathParam("id") Long id) {
    return view(find(id));
  }

  /** Adds an article. */
  @POST
  @Authenticated
  @Transactional
  public Response create(Input input) {
    requireEditor();
    Article a = new Article();
    apply(a, input);
    a.authorSubject = access.subject();
    a.createdAt = a.updatedAt;
    a.persist();
    return Response.status(201).entity(view(a)).build();
  }

  /** Changes an article. */
  @PUT
  @Path("/{id}")
  @Authenticated
  @Transactional
  public View update(@PathParam("id") Long id, Input input) {
    requireEditor();
    Article a = find(id);
    apply(a, input);
    return view(a);
  }

  /** Removes an article. */
  @DELETE
  @Path("/{id}")
  @Authenticated
  @Transactional
  public Response delete(@PathParam("id") Long id) {
    requireEditor();
    find(id).delete();
    return Response.noContent().build();
  }

  private void requireEditor() {
    if (!access.canEditArticles()) {
      throw new ForbiddenException();
    }
  }

  private static Article find(Long id) {
    Article a = Article.findById(id);
    if (a == null) {
      throw new NotFoundException();
    }
    return a;
  }

  private View view(Article a) {
    return new View(
        a.id,
        a.title,
        a.body,
        username(a.authorSubject),
        a.createdAt,
        a.updatedAt,
        access.canEditArticles());
  }

  /** An unreachable directory leaves the articles readable. */
  private String username(String subject) {
    try {
      return directory.find(subject).map(UserDirectory.User::username).orElse("unknown");
    } catch (UserDirectory.UnavailableException e) {
      return "unknown";
    }
  }

  private static void apply(Article a, Input input) {
    String title = input == null || input.title() == null ? "" : input.title().strip();
    if (title.isEmpty() || title.length() > Article.MAX_TITLE) {
      throw badRequest("A title of up to " + Article.MAX_TITLE + " characters is required");
    }
    String body = input.body() == null ? "" : input.body().strip();
    if (body.isEmpty() || body.length() > Article.MAX_BODY) {
      throw badRequest("A body of up to " + Article.MAX_BODY + " characters is required");
    }
    a.title = title;
    a.body = body;
    a.updatedAt = Instant.now();
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
