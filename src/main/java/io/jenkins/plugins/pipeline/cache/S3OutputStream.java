package io.jenkins.plugins.pipeline.cache;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

/**
 * {@link OutputStream} which allows writing an object to S3 directly. Heavily inspired by
 * https://gist.github.com/blagerweij/ad1dbb7ee2fff8bcffd372815ad310eb.
 */
public class S3OutputStream extends OutputStream {

    /**
     * Default chunk size is 5MB
     */
    protected static final int BUFFER_SIZE = 5*1024*1024;

    /**
     * The bucket-name on Amazon S3
     */
    private final String bucket;

    /**
     * The path (key) name within the bucket
     */
    private final String path;

    /**
     * The temporary buffer used for storing the chunks
     */
    private final byte[] buf;
    /**
     * Amazon S3 client.
     */
    private final AmazonS3 s3Client;
    /**
     * Collection of the etags for the parts that have been uploaded
     */
    private final List<PartETag> etags;
    /**
     * The position in the buffer
     */
    private int position;
    /**
     * The unique id for this upload
     */
    private String uploadId;
    /**
     * indicates whether the stream is still open / valid
     */
    private boolean open;

    /**
     * Creates a new S3 OutputStream
     *
     * @param s3Client the AmazonS3 client
     * @param bucket   name of the bucket
     * @param path     path within the bucket
     */
    public S3OutputStream(AmazonS3 s3Client, String bucket, String path) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.path = path;
        this.buf = new byte[BUFFER_SIZE];
        this.position = 0;
        this.etags = new ArrayList<>();
        this.open = true;
    }

    /**
     * Write an array to the S3 output stream.
     *
     * @param b the byte-array to append
     */
    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    /**
     * Writes an array to the S3 Output Stream
     *
     * @param byteArray the array to write
     * @param o         the offset into the array
     * @param l         the number of bytes to write
     */
    @Override
    public void write(final byte[] byteArray, final int o, final int l) {
        this.assertOpen();
        int ofs = o, len = l;
        int size;
        while (len > (size = this.buf.length - position)) {
            System.arraycopy(byteArray, ofs, this.buf, this.position, size);
            this.position += size;
            flushBufferAndRewind();
            ofs += size;
            len -= size;
        }
        System.arraycopy(byteArray, ofs, this.buf, this.position, len);
        this.position += len;
    }

    /**
     * Flushes the buffer by uploading a part to S3.
     */
    @Override
    public synchronized void flush() {
        this.assertOpen();
    }

    protected void flushBufferAndRewind() {
        if (uploadId == null) {
            final InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(this.bucket, this.path)
                    .withCannedACL(CannedAccessControlList.BucketOwnerFullControl);
            InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(request);
            this.uploadId = initResponse.getUploadId();
        }
        uploadPart();
        this.position = 0;
    }

    protected void uploadPart() {
        UploadPartResult uploadResult = this.s3Client.uploadPart(new UploadPartRequest()
                .withBucketName(this.bucket)
                .withKey(this.path)
                .withUploadId(this.uploadId)
                .withInputStream(new ByteArrayInputStream(buf, 0, this.position))
                .withPartNumber(this.etags.size() + 1)
                .withPartSize(this.position));
        this.etags.add(uploadResult.getPartETag());
    }

    @Override
    public void close() {
        if (this.open) {
            this.open = false;
            if (this.uploadId != null) {
                if (this.position > 0) {
                    uploadPart();
                }
                this.s3Client.completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, path, uploadId, etags));
            } else {
                final ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(this.position);
                final PutObjectRequest request =
                        new PutObjectRequest(this.bucket, this.path, new ByteArrayInputStream(this.buf, 0, this.position), metadata)
                                .withCannedAcl(CannedAccessControlList.BucketOwnerFullControl);
                this.s3Client.putObject(request);
            }
        }
    }

    @Override
    public void write(int b) {
        this.assertOpen();
        if (position >= this.buf.length) {
            flushBufferAndRewind();
        }
        this.buf[position++] = (byte) b;
    }

    private void assertOpen() {
        if (!this.open) {
            throw new IllegalStateException("Closed");
        }
    }
}