/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.demo.musicstore;

import com.couchbase.client.java.AsyncBucket;
import com.couchbase.client.java.CouchbaseAsyncCluster;
import com.couchbase.client.java.env.DefaultCouchbaseEnvironment;
import com.couchbase.client.java.query.Query;
import com.couchbase.client.java.query.SimpleQuery;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.demo.musicstore.sharedresource.SharedResource;
import io.vertx.demo.musicstore.sharedresource.SharedResources;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.rxjava.core.AbstractVerticle;
import io.vertx.rxjava.core.RxHelper;
import io.vertx.rxjava.ext.auth.jdbc.JDBCAuth;
import io.vertx.rxjava.ext.jdbc.JDBCClient;
import io.vertx.rxjava.ext.web.Router;
import io.vertx.rxjava.ext.web.client.WebClient;
import io.vertx.rxjava.ext.web.handler.BodyHandler;
import io.vertx.rxjava.ext.web.handler.CookieHandler;
import io.vertx.rxjava.ext.web.handler.FormLoginHandler;
import io.vertx.rxjava.ext.web.handler.SessionHandler;
import io.vertx.rxjava.ext.web.handler.StaticHandler;
import io.vertx.rxjava.ext.web.handler.UserSessionHandler;
import io.vertx.rxjava.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.rxjava.ext.web.sstore.LocalSessionStore;
import io.vertx.rxjava.ext.web.templ.FreeMarkerTemplateEngine;
import org.flywaydb.core.Flyway;
import rx.Single;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author Thomas Segismont
 */
public class MusicStoreVerticle extends AbstractVerticle {

  private DatasourceConfig datasourceConfig;
  private JDBCClient dbClient;
  private Properties dbQueries;
  private JDBCAuth authProvider;
  private SharedResource<CouchbaseAsyncCluster> couchbaseCluster;
  private SharedResource<AsyncBucket> albumCommentsBucket;
  private Properties couchbaseQueries;
  private FreeMarkerTemplateEngine templateEngine;

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    datasourceConfig = new DatasourceConfig(config().getJsonObject("datasource", new JsonObject()));
    dbClient = JDBCClient.createShared(vertx, datasourceConfig.toJson(), "MusicStoreDS");
    templateEngine = FreeMarkerTemplateEngine.create();
    createCouchbaseClient().doOnSuccess(cluster -> couchbaseCluster = cluster)
      .flatMap(v -> openBucket()).doOnSuccess(bucket -> albumCommentsBucket = bucket)
      .flatMap(v -> loadProperties("couchbase/queries.xml")).doOnSuccess(props -> couchbaseQueries = props)
      .flatMap(v -> setupBucket())
      .flatMap(v -> updateDB())
      .flatMap(v -> loadProperties("db/queries.xml")).doOnSuccess(props -> dbQueries = props)
      .doOnSuccess(v -> setupAuthProvider())
      .flatMap(v -> setupWebServer())
      .subscribe(startFuture::complete, startFuture::fail);
  }

  private Single<SharedResource<CouchbaseAsyncCluster>> createCouchbaseClient() {
    CouchbaseConfig couchbaseConfig = new CouchbaseConfig(config().getJsonObject("couchbase", new JsonObject()));
    return vertx.rxExecuteBlocking(fut -> {
      SharedResources sharedResources = SharedResources.INSTANCE;
      SharedResource<CouchbaseAsyncCluster> couchbaseCluster = sharedResources.getOrCreate("couchbaseCluster", () -> {
        return CouchbaseAsyncCluster.create(DefaultCouchbaseEnvironment.builder()
          .queryEnabled(true)
          .build(), couchbaseConfig.getNodes());
      }, CouchbaseAsyncCluster::disconnect);
      fut.complete(couchbaseCluster);
    });
  }

  private Single<SharedResource<AsyncBucket>> openBucket() {
    return vertx.rxExecuteBlocking(fut -> {
      SharedResources sharedResources = SharedResources.INSTANCE;
      SharedResource<AsyncBucket> asyncBucket = sharedResources.getOrCreate("commentsBucket", () -> {
        return couchbaseCluster.get().openBucket("album-comments").toSingle().toBlocking().value();
      }, bucket -> {
        bucket.close().toSingle().toBlocking().value();
      });
      fut.complete(asyncBucket);
    });
  }

  private Single<Void> setupBucket() {
    SimpleQuery findPrimaryIndex = Query.simple(couchbaseQueries.getProperty("findAlbumCommentsPrimaryIndex"));
    SimpleQuery createPrimaryIndex = Query.simple(couchbaseQueries.getProperty("createAlbumCommentsPrimaryIndex"));
    return albumCommentsBucket.get().query(findPrimaryIndex)
      .switchIfEmpty(albumCommentsBucket.get().query(createPrimaryIndex))
      .toSingle()
      .observeOn(RxHelper.scheduler(vertx))
      .map(v -> (Void) null);
  }

  private Single<Void> updateDB() {
    return vertx.rxExecuteBlocking(future -> {
      Flyway flyway = new Flyway();
      flyway.setDataSource(datasourceConfig.getUrl(), datasourceConfig.getUser(), datasourceConfig.getPassword());
      flyway.migrate();
      future.complete();
    });
  }

  private Single<Properties> loadProperties(String name) {
    return vertx.rxExecuteBlocking(fut -> {
      Properties properties = new Properties();
      try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
        properties.loadFromXML(is);
        fut.complete(properties);
      } catch (IOException e) {
        fut.fail(e);
      }
    });
  }

  private void setupAuthProvider() {
    authProvider = JDBCAuth.create(vertx, dbClient)
      .setAuthenticationQuery(dbQueries.getProperty("authenticateUser"))
      .setRolesQuery(dbQueries.getProperty("findRolesByUser"))
      .setPermissionsQuery(dbQueries.getProperty("findPermissionsByUser"));
  }

  private Single<Void> setupWebServer() {
    Router router = Router.router(vertx);

    SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
    sockJSHandler.bridge(new BridgeOptions()
      .addOutboundPermitted(new PermittedOptions().setAddressRegex("album\\.\\d+\\.comments\\.new")));
    router.route("/eventbus/*").handler(sockJSHandler);

    router.route().handler(BodyHandler.create());

    router.route().handler(CookieHandler.create());
    router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)));
    router.route().handler(UserSessionHandler.create(authProvider));

    IndexHandler indexHandler = new IndexHandler(dbClient, dbQueries, templateEngine);
    router.get("/").handler(indexHandler);
    router.get("/index.html").handler(indexHandler);

    router.get("/genres/:genreId").handler(new GenreHandler(dbClient, dbQueries, templateEngine));
    router.get("/albums/:albumId").handler(new AlbumHandler(dbClient, dbQueries, templateEngine));
    router.get("/artists/:artistId").handler(new ArtistHandler(dbClient, dbQueries, templateEngine));
    router.get("/covers/:albumId").handler(new CoverHandler(dbClient, dbQueries, WebClient.create(vertx)));

    router.get("/ajax/albums/:albumId/comments")
      .handler(new AjaxAlbumCommentsHandler(albumCommentsBucket.get(), couchbaseQueries, templateEngine));

    router.post("/api/albums/:albumId/comments")
      .consumes("text/plain")
      .handler(new AddAlbumCommentHandler(albumCommentsBucket.get()));

    router.get("/login").handler(new ReturnUrlHandler());
    router.get("/login").handler(rc -> templateEngine.rxRender(rc, "templates/login")
      .subscribe(rc.response()::end, rc::fail));
    router.post("/login").handler(FormLoginHandler.create(authProvider));

    router.get("/add_user").handler(rc -> templateEngine.rxRender(rc, "templates/add_user")
      .subscribe(rc.response()::end, rc::fail));
    router.post("/add_user").handler(new AddUserHandler(dbClient, dbQueries, authProvider));

    router.route().handler(StaticHandler.create());

    return vertx.createHttpServer()
      .requestHandler(router::accept)
      .rxListen(8080)
      .map(server -> null);
  }

  @Override
  public void stop(Future<Void> stopFuture) throws Exception {
    vertx.<Void>rxExecuteBlocking(fut -> {
      if (couchbaseCluster != null) {
        couchbaseCluster.release();
      }
      fut.complete();
    }).onErrorReturn(throwable -> {
      throwable.printStackTrace();
      return null;
    }).subscribe(stopFuture::complete, stopFuture::fail);
  }
}
