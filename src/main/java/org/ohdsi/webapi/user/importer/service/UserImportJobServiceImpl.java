package org.ohdsi.webapi.user.importer.service;

import com.cosium.spring.data.jpa.entity.graph.domain.EntityGraph;
import com.cosium.spring.data.jpa.entity.graph.domain.EntityGraphUtils;
import com.cronutils.model.definition.CronDefinition;
import com.odysseusinc.scheduler.model.ScheduledTask;
import com.odysseusinc.scheduler.service.BaseJobServiceImpl;
import org.ohdsi.webapi.Constants;
import org.ohdsi.webapi.job.JobTemplate;
import org.ohdsi.webapi.user.importer.converter.RoleGroupMappingConverter;
import org.ohdsi.webapi.user.importer.exception.JobAlreadyExistException;
import org.ohdsi.webapi.user.importer.model.LdapProviderType;
import org.ohdsi.webapi.user.importer.model.RoleGroupEntity;
import org.ohdsi.webapi.user.importer.model.RoleGroupMapping;
import org.ohdsi.webapi.user.importer.model.UserImport;
import org.ohdsi.webapi.user.importer.model.UserImportJob;
import org.ohdsi.webapi.user.importer.model.UserImportJobHistoryItem;
import org.ohdsi.webapi.user.importer.repository.RoleGroupRepository;
import org.ohdsi.webapi.user.importer.repository.UserImportJobHistoryItemRepository;
import org.ohdsi.webapi.user.importer.repository.UserImportJobRepository;
import org.ohdsi.webapi.user.importer.utils.RoleGroupUtils;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
public class UserImportJobServiceImpl extends BaseJobServiceImpl<UserImportJob> implements UserImportJobService {

  private final UserImportService userImportService;
  private final UserImportJobRepository jobRepository;
  private final RoleGroupRepository roleGroupRepository;
  private final UserImportJobHistoryItemRepository jobHistoryItemRepository;
  private final TransactionTemplate transactionTemplate;
  private final StepBuilderFactory stepBuilderFactory;
  private final JobBuilderFactory jobBuilders;
  private final JobTemplate jobTemplate;
  private EntityGraph jobWithMappingEntityGraph = EntityGraphUtils.fromName("jobWithMapping");

  public UserImportJobServiceImpl(TaskScheduler taskScheduler,
                                  CronDefinition cronDefinition,
                                  UserImportJobRepository jobRepository,
                                  UserImportService userImportService,
                                  RoleGroupRepository roleGroupRepository,
                                  UserImportJobHistoryItemRepository jobHistoryItemRepository,
                                  @Qualifier("transactionTemplateRequiresNew")
                                  TransactionTemplate transactionTemplate,
                                  StepBuilderFactory stepBuilderFactory,
                                  JobBuilderFactory jobBuilders,
                                  JobTemplate jobTemplate) {

    super(taskScheduler, cronDefinition, jobRepository);
    this.userImportService = userImportService;
    this.jobRepository = jobRepository;
    this.roleGroupRepository = roleGroupRepository;
    this.jobHistoryItemRepository = jobHistoryItemRepository;
    this.transactionTemplate = transactionTemplate;
    this.stepBuilderFactory = stepBuilderFactory;
    this.jobBuilders = jobBuilders;
    this.jobTemplate = jobTemplate;
  }

  @PostConstruct
  public void initializeJobs() {

    transactionTemplate.execute(transactionStatus -> {
      reassignAllJobs();
      return null;
    });
  }

  @Override
  protected void beforeCreate(UserImportJob job) {

    UserImportJob exists = jobRepository.findByProviderType(job.getProviderType());
    if (Objects.nonNull(exists)) {
      throw new JobAlreadyExistException();
    }
  }

  @Override
  protected List<UserImportJob> getActiveJobs() {

    return jobRepository.findAllByEnabledTrueAndIsClosedFalse(jobWithMappingEntityGraph);
  }

  @Override
  protected void updateAdditionalFields(UserImportJob exists, UserImportJob updated) {

    exists.setProviderType(updated.getProviderType());
    List<RoleGroupEntity> existMapping = exists.getRoleGroupMapping();
    List<RoleGroupEntity> updatedMapping = updated.getRoleGroupMapping();
    List<RoleGroupEntity> deleted = RoleGroupUtils.findDeleted(existMapping, updatedMapping);
    List<RoleGroupEntity> created = RoleGroupUtils.findCreated(existMapping, updatedMapping);
    created.forEach(c -> c.setUserImportJob(exists));
    if (!deleted.isEmpty()) {
      roleGroupRepository.delete(deleted);
    }
    if (!created.isEmpty()) {
      existMapping.addAll(roleGroupRepository.save(created));
    }
  }

  @Override
  protected ScheduledTask<UserImportJob> buildScheduledTask(UserImportJob userImportJob) {

    return new UserImportScheduledTask(userImportJob);
  }

  @Override
  public List<UserImportJob> getJobs() {

    return jobRepository.findUserImportJobsBy().map(this::assignNextExecution).collect(Collectors.toList());
  }

  @Override
  public Optional<UserImportJob> getJob(Long id) {

    return Optional.ofNullable(jobRepository.findOne(id)).map(this::assignNextExecution);
  }

  public void runImportUsersTask(UserImport userImport) {

    JobParameters jobParameters = new JobParametersBuilder()
            .addString(Constants.Params.JOB_NAME, String.format("Users import for %s ran by user request", getProviderName(userImport.getProvider())))
            .addString(Constants.Params.USER_IMPORT_ID, String.valueOf(userImport.getId()))
            .toJobParameters();
    Job job = jobBuilders.get(Constants.USERS_IMPORT)
            .start(userImportStep())
            .build();
    jobTemplate.launch(job, jobParameters);
  }

  @Override
  public Stream<UserImportJobHistoryItem> getJobHistoryItems(LdapProviderType providerType) {

    return jobHistoryItemRepository.findByUserImportProvider(providerType);
  }

  @Override
  public Optional<UserImportJobHistoryItem> getLatestHistoryItem(LdapProviderType providerType) {

    return jobHistoryItemRepository.findFirstByUserImportProviderOrderByEndTimeDesc(providerType);
  }

  Step userImportStep() {

    UserImportTasklet userImportTasklet = new UserImportTasklet(transactionTemplate, userImportService);
    return stepBuilderFactory.get("importUsers")
            .tasklet(userImportTasklet)
            .build();
  }

  Job buildJobForUserImportTasklet() {

    FindUsersToImportTasklet findUsersTasklet = new FindUsersToImportTasklet(transactionTemplate, userImportService);
    Step findUsersStep = stepBuilderFactory.get("findUsersForImport")
            .tasklet(findUsersTasklet)
            .build();

    return jobBuilders.get(Constants.USERS_IMPORT)
            .start(findUsersStep)
            .next(userImportStep())
            .build();
  }

  private class UserImportScheduledTask extends ScheduledTask<UserImportJob> {

    UserImportScheduledTask(UserImportJob job) {
      super(job);
    }

    @Override
    public void run() {
      List<RoleGroupEntity> roleGroupEntities = job.getRoleGroupMapping();

      RoleGroupMapping roleGroupMapping = transactionTemplate.execute(transactionStatus ->
              RoleGroupMappingConverter.convertRoleGroupMapping(job.getProviderType().getValue(), roleGroupEntities));

      UserImport userImport = userImportService.createUserImportJob(job.getProviderType(),
              job.getPreserveRoles(), null, roleGroupMapping);

      JobParameters jobParameters = new JobParametersBuilder()
              .addString(Constants.Params.JOB_NAME, String.format("Users import for %s", getProviderName(job.getProviderType())))
              .addString(Constants.Params.JOB_AUTHOR, "system")
              .addString(Constants.Params.USER_IMPORT_ID, String.valueOf(userImport.getId()))
              .toJobParameters();

      Job batchJob = buildJobForUserImportTasklet();
      jobTemplate.launch(batchJob, jobParameters);
    }
  }

  private String getProviderName(LdapProviderType providerType) {
    switch (providerType){
      case ACTIVE_DIRECTORY:
        return "Active Directory";
      case LDAP:
        return "LDAP Server";
      default:
        return "Unknown";
    }
  }
}
