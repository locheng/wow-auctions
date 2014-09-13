package com.radcortez.wow.auctions.batch;

import com.radcortez.wow.auctions.business.WoWBusiness;
import com.radcortez.wow.auctions.entity.*;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.batch.operations.JobOperator;
import javax.batch.runtime.BatchRuntime;
import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobExecution;
import javax.inject.Inject;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static com.radcortez.wow.auctions.batch.util.BatchTestHelper.keepTestAlive;
import static org.apache.commons.io.FileUtils.copyInputStreamToFile;
import static org.apache.commons.io.FileUtils.getFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author Roberto Cortez
 */
@RunWith(Arquillian.class)
public class JobTest {
    @Inject
    private WoWBusiness woWBusiness;

    @Deployment
    public static WebArchive createDeployment() {
        File[] requiredLibraries = Maven.resolver().loadPomFromFile("pom.xml")
                                        .resolve("commons-io:commons-io")
                                        .withTransitivity().asFile();

        WebArchive war = ShrinkWrap.create(WebArchive.class)
                                   .addAsLibraries(requiredLibraries)
                                   .addPackages(true, "com.radcortez.wow.auctions")
                                   .addAsWebInfResource("META-INF/beans.xml")
                                   .addAsResource("META-INF/persistence.xml")
                                   .addAsResource("META-INF/batch-jobs/prepare-job.xml")
                                   .addAsResource("META-INF/batch-jobs/files-job.xml")
                                   .addAsResource("META-INF/batch-jobs/process-job.xml")
                                   .addAsResource("samples/auction-data-sample.json");
        System.out.println(war.toString(true));
        return war;
    }

    @Test
    @InSequence(1)
    public void testPrepareJob() throws Exception {
        JobOperator jobOperator = BatchRuntime.getJobOperator();
        Long executionId = jobOperator.start("prepare-job", new Properties());

        JobExecution jobExecution = keepTestAlive(jobOperator, executionId);

        List<Realm> realms = woWBusiness.listRealms();
        assertFalse(realms.isEmpty());

        assertEquals(BatchStatus.COMPLETED, jobExecution.getBatchStatus());
    }

    @Test
    @InSequence(2)
    public void testFilesJob() throws Exception {
        JobOperator jobOperator = BatchRuntime.getJobOperator();
        Long executionId = jobOperator.start("files-job", new Properties());

        JobExecution jobExecution = keepTestAlive(jobOperator, executionId);

        List<AuctionFile> auctionFilesEU = woWBusiness.findAuctionFilesByRegionToDownload(Realm.Region.EU);
        assertFalse(auctionFilesEU.isEmpty());

        assertEquals(BatchStatus.COMPLETED, jobExecution.getBatchStatus());
    }

    @Test
    @InSequence(3)
    public void testProcessJob() throws Exception {
        Realm realm = woWBusiness.findRealmByNameOrSlug("Hellscream", Realm.Region.EU);
        RealmFolder realmFolder = woWBusiness.findRealmFolderById(realm.getId(), FolderType.FI);
        AuctionFile auctionFile = new AuctionFile();
        auctionFile.setUrl("test");
        auctionFile.setLastModified(LocalDate.now().toEpochDay());
        auctionFile.setFileName("auction-data-sample.json");
        auctionFile.setFileStatus(FileStatus.DOWNLOADED);
        auctionFile.setRealm(realm);
        woWBusiness.createAuctionFile(auctionFile);

        copyInputStreamToFile(
                Thread.currentThread().getContextClassLoader().getResourceAsStream("samples/auction-data-sample.json"),
                getFile(realmFolder.getPath() + "/auction-data-sample.json"));

        auctionFile = woWBusiness.findAuctionFilesByRealmToProcess(realm.getId()).get(0);

        Properties jobParameters = new Properties();
        jobParameters.setProperty("realmId", realm.getId().toString());
        jobParameters.setProperty("auctionFileId", auctionFile.getId().toString());

        JobOperator jobOperator = BatchRuntime.getJobOperator();
        Long executionId = jobOperator.start("process-job", jobParameters);

        JobExecution jobExecution = keepTestAlive(jobOperator, executionId);

        assertEquals(BatchStatus.COMPLETED, jobExecution.getBatchStatus());
    }
}
