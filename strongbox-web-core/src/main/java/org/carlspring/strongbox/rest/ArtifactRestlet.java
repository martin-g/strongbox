package org.carlspring.strongbox.rest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.carlspring.maven.commons.util.ArtifactUtils;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.http.range.ByteRange;
import org.carlspring.strongbox.http.range.ByteRangeHeaderParser;
import org.carlspring.strongbox.io.ArtifactInputStream;
import org.carlspring.strongbox.security.jaas.authentication.AuthenticationException;
import org.carlspring.strongbox.services.ArtifactManagementService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.metadata.MetadataManager;
import org.carlspring.strongbox.storage.metadata.MetadataType;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.resolvers.ArtifactResolutionException;
import org.carlspring.strongbox.storage.resolvers.ArtifactStorageException;
import org.carlspring.strongbox.util.MessageDigestUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Martin Todorov
 */
@Component
@Path("/storages")
public class ArtifactRestlet
        extends BaseRestlet
{

    private static final Logger logger = LoggerFactory.getLogger(ArtifactRestlet.class);

    @Autowired
    private ArtifactManagementService artifactManagementService;
    
    @Autowired
    private MetadataManager metadataManager;
    
    @Autowired
    private ConfigurationManager configurationManager;

    @PUT
    @Path("{storageId}/{repositoryId}/{path:.*}")
    public Response upload(@PathParam("storageId") String storageId,
                           @PathParam("repositoryId") String repositoryId,
                           @PathParam("path") String path,
                           @Context HttpHeaders headers,
                           @Context HttpServletRequest request,
                           InputStream is)
            throws IOException,
                   AuthenticationException,
                   NoSuchAlgorithmException
    {
        handleAuthentication(storageId, repositoryId, path, headers, request);

        try
        {
            artifactManagementService.store(storageId, repositoryId, path, is);

            return Response.ok().build();
        }
        catch (IOException e)
        {
            // TODO: Figure out if this is the correct response type...
            logger.error(e.getMessage(), e);

            // return Response.status(Response.Status.FORBIDDEN).entity("Access denied!").build();
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("{storageId}/{repositoryId}/{path:.*}")
    public Response download(@PathParam("storageId") String storageId,
                             @PathParam("repositoryId") String repositoryId,
                             @PathParam("path") String path,
                             @Context HttpServletRequest request,
                             @Context HttpHeaders headers)
            throws IOException,
                   InstantiationException,
                   IllegalAccessException,
                   ClassNotFoundException,
                   AuthenticationException
    {
        handleAuthentication(storageId, repositoryId, path, headers, request);

        logger.debug(" repository = " + repositoryId + ", path = " + path);

        Storage storage = artifactManagementService.getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);
        if (!repository.isInService())
        {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }

        Response.ResponseBuilder responseBuilder;

        InputStream is;
        try
        {
            if (isRangedRequest(headers))
            {
                responseBuilder = handlePartialDownload(storageId, repositoryId, path, headers);
            }
            else
            {
                is = artifactManagementService.resolve(storageId, repositoryId, path);

                responseBuilder = Response.ok(is);
            }
        }
        catch (ArtifactResolutionException e)
        {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        setMediaTypeHeader(path, responseBuilder);

        responseBuilder.header("Accept-Ranges", "bytes");

        setHeadersForChecksums(storageId, repositoryId, path, responseBuilder);

        return responseBuilder.build();
    }

    private Response.ResponseBuilder handlePartialDownload(String storageId,
                                                           String repositoryId,
                                                           String path,
                                                           HttpHeaders headers)
            throws IOException
    {
        ByteRangeHeaderParser parser = new ByteRangeHeaderParser(headers.getRequestHeaders().getFirst("Range"));
        List<ByteRange> ranges = parser.getRanges();

        if (ranges.size() == 1)
        {
            logger.debug("Received request for a partial download with a single range.");

            return handlePartialDownloadWithSingleRange(storageId, repositoryId, path, ranges.get(0));
        }
        else
        {
            logger.debug("Received request for a partial download with multiple ranges.");

            return handlePartialDownloadWithMultipleRanges(storageId, repositoryId, path, ranges);
        }
    }

    private Response.ResponseBuilder handlePartialDownloadWithSingleRange(String storageId,
                                                                          String repositoryId,
                                                                          String path,
                                                                          ByteRange byteRange)
            throws IOException
    {
        ArtifactInputStream ais = (ArtifactInputStream) artifactManagementService.resolve(storageId, repositoryId, path);
        if (byteRange.getOffset() < ais.getLength())
        {
            // TODO: If OK: Return: 206 Partial Content
            // TODO:     Set headers:
            // TODO:         Accept-Ranges: bytes
            // TODO:         Content-Length: 64656927
            // TODO:         Content-Range: bytes 100-64656926/64656927
            // TODO:         Content-Type: application/jar
            // TODO:         Pragma: no-cache

            ais.setCurrentByteRange(byteRange);
            //noinspection ResultOfMethodCallIgnored
            ais.skip(byteRange.getOffset());

            Response.ResponseBuilder responseBuilder = prepareResponseBuilderForPartialRequest(ais);
            responseBuilder.header("Content-Length", calculatePartialRangeLength(byteRange, ais.getLength()));
            responseBuilder.status(Response.Status.PARTIAL_CONTENT);

            return responseBuilder;
        }
        else
        {
            // TODO: Else: If the byte-range-set is unsatisfiable, the server SHOULD return
            // TODO:       a response with a status of 416 (Requested range not satisfiable).

            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
    }

    private Response.ResponseBuilder handlePartialDownloadWithMultipleRanges(String storageId,
                                                                             String repositoryId,
                                                                             String path,
                                                                             List<ByteRange> byteRanges)
            throws IOException
    {
        // TODO: To be handled as part of SB-367.

        ArtifactInputStream ais = (ArtifactInputStream) artifactManagementService.resolve(storageId, repositoryId, path);
        // TODO: This is not the right check
        if (ais.getCurrentByteRange().getOffset() >= ais.getLength())
        {
            // TODO: If OK: Return: 206 Partial Content
            // TODO:     For each range:
            // TODO:         Set headers:
            // TODO:             Content-Type: application/jar
            // TODO:             Content-Length: 64656927
            // TODO:             Accept-Ranges: bytes
            // TODO:             Content-Range: bytes 100-64656926/64656927
            // TODO:             Pragma: no-cache

            Response.ResponseBuilder responseBuilder = prepareResponseBuilderForPartialRequest(ais);

            // TODO: Add multipart content here

            return responseBuilder;
        }
        else
        {
            // TODO: Else: If the byte-range-set is unsatisfiable, the server SHOULD return
            // TODO:       a response with a status of 416 (Requested range not satisfiable).

            return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE);
        }
    }

    private long calculatePartialRangeLength(ByteRange byteRange, long length)
    {
        if (byteRange.getLimit() > 0 && byteRange.getOffset() > 0)
        {
            logger.debug("Partial content byteRange.getOffset: " + byteRange.getOffset());
            logger.debug("Partial content byteRange.getLimit: " + byteRange.getLimit());
            logger.debug("Partial content length: " + (byteRange.getLimit() - byteRange.getOffset()));

            return byteRange.getLimit() - byteRange.getOffset();
        }
        else if (length > 0 && byteRange.getOffset() > 0 && byteRange.getLimit() == 0)
        {
            logger.debug("Partial content length: " + (length - byteRange.getOffset()));

            return length - byteRange.getOffset();
        }
        else
        {
            return -1;
        }
    }

    private Response.ResponseBuilder prepareResponseBuilderForPartialRequest(ArtifactInputStream ais)
    {
        Response.ResponseBuilder responseBuilder = Response.ok(ais).status(Response.Status.PARTIAL_CONTENT);
        responseBuilder.header("Accept-Ranges", "bytes");
        // responseBuilder.header("Content-Length", ais.getLength());
        responseBuilder.header("Content-Range", "bytes " + ais.getCurrentByteRange().getOffset() + "-" + (ais.getLength() - 1) + "/" + ais.getLength());
        responseBuilder.header("Content-Type", ais.getLength());
        responseBuilder.header("Pragma", "no-cache");

        return responseBuilder;
    }

    private void setMediaTypeHeader(String path, Response.ResponseBuilder responseBuilder)
    {
        // TODO: This is far from optimal and will need to have a content type approach at some point:
        if (ArtifactUtils.isChecksum(path))
        {
            responseBuilder.type(MediaType.TEXT_PLAIN);
        }
        else if (ArtifactUtils.isMetadata(path))
        {
            responseBuilder.type(MediaType.APPLICATION_XML);
        }
        else
        {
            responseBuilder.type(MediaType.APPLICATION_OCTET_STREAM);
        }
    }

    private boolean isRangedRequest(HttpHeaders headers)
    {
        if (headers == null)
        {
            return false;
        }

        String contentRange = headers.getRequestHeaders() != null &&
                              headers.getRequestHeaders().getFirst("Range") != null ?
                              headers.getRequestHeaders().getFirst("Range") : null;

        return contentRange != null &&
               !contentRange.equals("0/*") && !contentRange.equals("0-") && !contentRange.equals("0");
    }

    private void setHeadersForChecksums(String storageId,
                                        String repositoryId,
                                        String path,
                                        Response.ResponseBuilder responseBuilder)
            throws IOException
    {
        Storage storage = artifactManagementService.getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);
        if (!repository.isChecksumHeadersEnabled())
        {
            return;
        }

        InputStream isMd5;
        //noinspection EmptyCatchBlock
        try
        {
            isMd5 = artifactManagementService.resolve(storageId, repositoryId, path + ".md5");
            responseBuilder.header("Checksum-MD5", MessageDigestUtils.readChecksumFile(isMd5));
        }
        catch (IOException e)
        {
            // This can occur if there is no checksum
            logger.warn("There is no MD5 checksum for "  + storageId + "/" + repositoryId + "/" + path);
        }

        InputStream isSha1;
        //noinspection EmptyCatchBlock
        try
        {
            isSha1 = artifactManagementService.resolve(storageId, repositoryId, path + ".sha1");
            responseBuilder.header("Checksum-SHA1", MessageDigestUtils.readChecksumFile(isSha1));
        }
        catch (IOException e)
        {
            // This can occur if there is no checksum
            logger.warn("There is no SHA1 checksum for "  + storageId + "/" + repositoryId + "/" + path);
        }
    }

    @POST
    @Path("copy/{path:.*}")
    public Response copy(@PathParam("path") String path,
                         @QueryParam("srcStorageId") String srcStorageId,
                         @QueryParam("srcRepositoryId") String srcRepositoryId,
                         @QueryParam("destStorageId") String destStorageId,
                         @QueryParam("destRepositoryId") String destRepositoryId)
            throws IOException
    {
        logger.debug("Copying " + path +
                     " from " + srcStorageId + ":" + srcRepositoryId +
                     " to " + destStorageId + ":" + destRepositoryId + "...");

        try
        {
            artifactManagementService.copy(srcStorageId, srcRepositoryId, path, destStorageId, destRepositoryId);
        }
        catch (ArtifactStorageException e)
        {
            if (artifactManagementService.getStorage(srcStorageId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The source storageId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(destStorageId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The destination storageId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(srcStorageId).getRepository(srcRepositoryId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The source repositoryId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(destStorageId).getRepository(destRepositoryId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The destination repositoryId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(srcStorageId) != null &&
                     artifactManagementService.getStorage(srcStorageId).getRepository(srcRepositoryId) != null &&
                     !new File(artifactManagementService.getStorage(srcStorageId)
                                                        .getRepository(srcRepositoryId).getBasedir(), path).exists())
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The source path does not exist!")
                               .build();
            }

            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(e.getMessage())
                           .build();
        }

        return Response.ok().build();
    }

    @DELETE
    @Path("{storageId}/{repositoryId}/{path:.*}")
    public Response delete(@PathParam("storageId") String storageId,
                           @PathParam("repositoryId") String repositoryId,
                           @PathParam("path") String path,
                           @QueryParam("force") @DefaultValue("false") boolean force)
            throws IOException
    {
        logger.debug("DELETE: " + path);
        logger.debug(storageId + ":" + repositoryId + ": " + path);

        try
        {
            artifactManagementService.delete(storageId, repositoryId, path, force);
            deleteMethodFromMetadaInFS(storageId,repositoryId,path);

        }
        catch (ArtifactStorageException e)
        {
            if (artifactManagementService.getStorage(storageId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The specified storageId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(storageId).getRepository(repositoryId) == null)
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The specified repositoryId does not exist!")
                               .build();
            }
            else if (artifactManagementService.getStorage(storageId) != null &&
                     artifactManagementService.getStorage(storageId).getRepository(repositoryId) != null &&
                     !new File(artifactManagementService.getStorage(storageId)
                                                        .getRepository(repositoryId).getBasedir(), path).exists())
            {
                return Response.status(Response.Status.NOT_FOUND)
                               .entity("The specified path does not exist!")
                               .build();
            }

            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(e.getMessage())
                           .build();
        }

        return Response.ok().build();
    }
    
    protected void deleteMethodFromMetadaInFS(String storageId, String repositoryId, String metadataPath)
    {
        Storage storage = configurationManager.getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);
        final File repoPath = new File(repository.getBasedir());
        
        try
        {
            File artifactFile = new File(repoPath, metadataPath).getCanonicalFile();
            if (!artifactFile.isFile())
            {
                String version = artifactFile.getPath().substring(artifactFile.getPath().lastIndexOf("/")+1);
                java.nio.file.Path path = Paths.get(artifactFile.getPath().substring(0, artifactFile.getPath().lastIndexOf("/")));
                Metadata metadata = metadataManager.readMetadata(path);
                if (metadata != null && metadata.getVersioning() != null)
                {
                    if (metadata.getVersioning().getVersions().contains(version))
                    {
                        metadata.getVersioning().getVersions().remove(version);
                        metadataManager.storeMetadata(path, null, metadata, MetadataType.ARTIFACT_ROOT_LEVEL);
                    }
                }
            }
        }
        catch (IOException | XmlPullParserException | NoSuchAlgorithmException e)
        {
            // We wont to do anything in this case because it doesn't have impact in the deletion
        }
    }        
}

