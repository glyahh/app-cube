/* eslint-disable */
// @ts-ignore

export type AppAddRequest = {
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
  isBeta?: number;
  loopIds?: number[];
};

export type AppAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type AppAdminGetVoUsingGetParams = {
  id: number;
};

export type AppAdminGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseAppVO;
};

export type AppAdminListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppAdminOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppAdminUpdateRequest = {
  id?: number;
  appName?: string;
  cover?: string;
  priority?: number;
};

export type AppAdminUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppApplyAgreeUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppApplyListMyHistoryUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListApplyHistoryVO;
};

export type AppApplyListPendingUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListApplyVO;
};

export type AppApplyRejectUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppApplyUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppChatHistoryPageVO = {
  records?: ChatHistory[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  isBeta?: number;
};

export type AppDeployRequest = {
  appId?: number;
};

export type AppDeployUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseString;
};

export type AppDownloadAppIdUsingGetParams = {
  appId: number;
};

export type AppDownloadAppIdUsingGetResponses = {
  /**
   * OK
   */
  200: unknown;
};

export type AppGetVoUsingGetParams = {
  id: string;
};

export type AppGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseAppVO;
};

export type AppGoodListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppLoopAddUsingPostParams = {
  appId: number;
  loopId: number;
};

export type AppLoopAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type AppLoopBindUsingPostBody = number[];

export type AppLoopBindUsingPostParams = {
  appId: number;
};

export type AppLoopBindUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type AppLoopListVoUsingPostParams = {
  appId: number;
};

export type AppLoopListVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListMapStringObject;
};

export type AppLoopRemoveUsingPostParams = {
  appId: number;
  loopId: number;
};

export type AppLoopRemoveUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type ApplyHistoryVO = {
  applyId?: string;
  operate?: number;
  appId?: string;
  appName?: string;
  appPropriety?: number;
  applyReason?: string;
  status?: number;
  reviewRemark?: string;
  createTime?: string;
  reviewTime?: string;
};

export type ApplyVO = {
  applyId?: number;
  userId?: number;
  userAvatar?: string;
  operate?: number;
  appId?: number;
  reason?: string;
};

export type AppMyListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: number;
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
  deployKey?: string;
  priority?: number;
  userId?: number;
  isDelete?: number;
  isWorkflow?: number;
  isDeployed?: number;
};

export type AppStaticProjectFilesAppIdUsingGetParams = {
  appId: number;
};

export type AppStaticProjectFilesAppIdUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseListProjectFileVO;
};

export type AppUndeployUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppUpdateRequest = {
  id?: number;
  appName?: string;
  cover?: string;
};

export type AppUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppVO = {
  id?: number;
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
  deployKey?: string;
  deployedTime?: string;
  priority?: number;
  userId?: number;
  editTime?: string;
  createTime?: string;
  updateTime?: string;
  userVO?: UserVO;
  hasGeneratedCode?: boolean;
  isBeta?: number;
};

export type BaseResponseAppChatHistoryPageVO = {
  code?: number;
  data?: AppChatHistoryPageVO;
  message?: string;
};

export type BaseResponseAppVO = {
  code?: number;
  data?: AppVO;
  message?: string;
};

export type BaseResponseBoolean = {
  code?: number;
  data?: boolean;
  message?: string;
};

export type BaseResponseInteger = {
  code?: number;
  data?: number;
  message?: string;
};

export type BaseResponseListApplyHistoryVO = {
  code?: number;
  data?: ApplyHistoryVO[];
  message?: string;
};

export type BaseResponseListApplyVO = {
  code?: number;
  data?: ApplyVO[];
  message?: string;
};

export type BaseResponseListChatHistoryVO = {
  code?: number;
  data?: ChatHistoryVO[];
  message?: string;
};

export type BaseResponseListLoopVO = {
  code?: number;
  data?: LoopVO[];
  message?: string;
};

export type BaseResponseListMapStringObject = {
  code?: number;
  data?: Record<string, Record<string, unknown>>[];
  message?: string;
};

export type BaseResponseListProjectFileVO = {
  code?: number;
  data?: ProjectFileVO[];
  message?: string;
};

export type BaseResponseLoginUserVO = {
  code?: number;
  data?: LoginUserVO;
  message?: string;
};

export type BaseResponseLong = {
  code?: number;
  data?: number;
  message?: string;
};

export type BaseResponseLoopVO = {
  code?: number;
  data?: LoopVO;
  message?: string;
};

export type BaseResponseObject = {
  code?: number;
  data?: Record<string, unknown>;
  message?: string;
};

export type BaseResponsePageAppVO = {
  code?: number;
  data?: PageAppVO;
  message?: string;
};

export type BaseResponsePageChatHistory = {
  code?: number;
  data?: PageChatHistory;
  message?: string;
};

export type BaseResponsePageUserChatHistoryItemVO = {
  code?: number;
  data?: PageUserChatHistoryItemVO;
  message?: string;
};

export type BaseResponsePageUserVO = {
  code?: number;
  data?: PageUserVO;
  message?: string;
};

export type BaseResponsePreference = {
  code?: number;
  data?: Preference;
  message?: string;
};

export type BaseResponseString = {
  code?: number;
  data?: string;
  message?: string;
};

export type BaseResponseUser = {
  code?: number;
  data?: User;
  message?: string;
};

export type BaseResponseUserPersonalizationVO = {
  code?: number;
  data?: UserPersonalizationVO;
  message?: string;
};

export type BaseResponseUserVO = {
  code?: number;
  data?: UserVO;
  message?: string;
};

export type BaseResponseVoid = {
  code?: number;
  data?: Record<string, unknown>;
  message?: string;
};

export type ChatGenCodeUsingGetParams = {
  appId: number;
  message: string;
  loopId?: number;
};

export type ChatGenCodeUsingGetResponses = {
  /**
   * OK
   */
  200: ServerSentEventString[];
};

export type ChatGenWorkflowUsingGetParams = {
  appId: number;
  message: string;
};

export type ChatGenWorkflowUsingGetResponses = {
  /**
   * OK
   */
  200: ServerSentEventString[];
};

export type ChatHistory = {
  id?: number;
  message?: string;
  messageType?: string;
  appId?: number;
  userId?: number;
  auditAction?: string;
  auditHitRule?: string;
  createTime?: string;
  updateTime?: string;
  isDelete?: number;
};

export type ChatHistoryAdminUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageChatHistory;
};

export type ChatHistoryAppAppIdUsingGetParams = {
  appId: number;
  lastCreateTime?: string;
  size?: number;
};

export type ChatHistoryAppAppIdUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseAppChatHistoryPageVO;
};

export type ChatHistoryDeleteByAppIdAppIdUsingPostParams = {
  appId: number;
};

export type ChatHistoryDeleteByAppIdAppIdUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type ChatHistoryMyChatHistoryUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageUserChatHistoryItemVO;
};

export type ChatHistoryOpenApiExportAppIdUsingGetParams = {
  appId: number;
};

export type ChatHistoryOpenApiExportAppIdUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseListChatHistoryVO;
};

export type ChatHistoryQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: number;
  message?: string;
  messageType?: string;
  appId?: number;
  appName?: string;
  userId?: number;
  lastCreateTime?: string;
};

export type ChatHistoryRoundCountAppIdUsingGetParams = {
  appId: number;
};

export type ChatHistoryRoundCountAppIdUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseInteger;
};

export type ChatHistorySaveUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type ChatHistoryVO = {
  id?: number;
  message?: string;
  messageType?: string;
  appId?: number;
  userId?: number;
  auditAction?: string;
  auditHitRule?: string;
  createTime?: string;
  updateTime?: string;
};

export type DeleteRequest = {
  id?: number;
};

export type DeployDeployKeyUsingGetParams = {
  deployKey: string;
};

export type DeployDeployKeyUsingGetResponses = {
  /**
   * OK
   */
  200: string;
};

export type LoginUserVO = {
  id?: number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
  updateTime?: string;
};

export type LoopAddRequest = {
  loopName?: string;
  description?: string;
  cover?: string;
  workflowJson?: string;
  sourceType?: string;
  visibility?: string;
};

export type LoopAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type LoopAdminListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListLoopVO;
};

export type LoopAdminUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type LoopApplyUsingPostParams = {
  loopId: number;
  reason?: string;
};

export type LoopApplyUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type LoopGetVoUsingGetParams = {
  id: number;
};

export type LoopGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseLoopVO;
};

export type LoopGoodListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListLoopVO;
};

export type LoopPublicListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListLoopVO;
};

export type LoopAdminListApplyUsingPostParams = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  searchText?: string;
};

export type LoopAdminApplyApproveUsingPostParams = {
  applyId: number;
};

export type LoopAdminApplyRejectUsingPostParams = {
  applyId: number;
  reviewRemark?: string;
};

export type LoopMyListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseListLoopVO;
};

export type LoopOpenApiDeleteUsingPostParams = {
  id: number;
};

export type LoopOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type LoopOpenApiImportUsingPostBody = string;

export type LoopMarketImportUsingPostParams = {
  loopId: number;
};

export type LoopOpenApiImportUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type LoopQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  searchText?: string;
};

export type LoopUpdateRequest = {
  id?: number;
  loopName?: string;
  description?: string;
  cover?: string;
  workflowJson?: string;
  visibility?: string;
  priority?: number;
};

export type LoopUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseVoid;
};

export type LoopVO = {
  id?: number;
  loopName?: string;
  description?: string;
  cover?: string;
  userId?: number;
  priority?: number;
  workflowJson?: string;
  compiledPrompt?: string;
  sourceType?: string;
  visibility?: string;
  createTime?: string;
  updateTime?: string;
};

export type PageAppVO = {
  records?: AppVO[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageChatHistory = {
  records?: ChatHistory[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageUser = {
  records?: User[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageUserChatHistoryItemVO = {
  records?: UserChatHistoryItemVO[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageUserVO = {
  records?: UserVO[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type Preference = {
  expandExecutionProcess?: boolean;
  alertOnStartupFailure?: boolean;
  alertOnRuntimeFailure?: boolean;
  autoModelProxyFallback?: boolean;
  autoNameProject?: boolean;
};

export type ProjectFileVO = {
  path?: string;
  language?: string;
  content?: string;
  updatedAt?: number;
};

export type ServerSentEventString = object;

export type StaticDeployKeyUsingGetParams = {
  deployKey: string;
};

export type StaticDeployKeyUsingGetResponses = {
  /**
   * OK
   */
  200: string;
};

export type TestUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseObject;
};

export type User = {
  id?: number;
  userAccount?: string;
  userPassword?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  autoModelProxyFallback?: boolean;
  autoNameProject?: boolean;
  editTime?: string;
  createTime?: string;
  updateTime?: string;
  isDelete?: number;
};

export type UserAddRequest = {
  userName?: string;
  userAccount?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type UserAppApplyHandleRequest = {
  applyId?: number;
  applyReason?: string;
  reviewRemark?: string;
};

export type UserAppApplyRequest = {
  appId?: number;
  appPropriety?: number;
  operate?: number;
  applyReason?: string;
};

export type UserChatHistoryItemVO = {
  id?: number;
  message?: string;
  messageType?: string;
  appId?: number;
  appName?: string;
  createTime?: string;
  updateTime?: string;
  summaryText?: string;
  naturalLanguage?: string;
};

export type UserGetInfoIdUsingGetParams = {
  id: number;
};

export type UserGetInfoIdUsingGetResponses = {
  /**
   * OK
   */
  200: User;
};

export type UserGetLoginUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseLoginUserVO;
};

export type UserGetUsingGetParams = {
  id: number;
};

export type UserGetUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUser;
};

export type UserGetVoUsingGetParams = {
  id: number;
};

export type UserGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUserVO;
};

export type UserListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageUserVO;
};

export type UserListUsingGetResponses = {
  /**
   * OK
   */
  200: User[];
};

export type UserLoginRequest = {
  userAccount?: string;
  userPassword?: string;
};

export type UserLoginUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLoginUserVO;
};

export type UserLogoutUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserPageUsingGetParams = {
  page: PageUser;
};

export type UserPageUsingGetResponses = {
  /**
   * OK
   */
  200: PageUser;
};

export type UserPersonalizationUpdateRequest = {
  appStyle?: string;
  answerStyle?: string;
};

export type UserPersonalizationUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUserPersonalizationVO;
};

export type UserPersonalizationUsingPutResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserPersonalizationVO = {
  appStyle?: string;
  answerStyle?: string;
};

export type UserQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: number;
  userName?: string;
  userAccount?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserRegisterRequest = {
  userAccount?: string;
  userPassword?: string;
  checkPassword?: string;
};

export type UserRegisterUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type UserRemoveIdUsingDeleteParams = {
  id: number;
};

export type UserRemoveIdUsingDeleteResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserSaveUsingPostResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserUpdateRequest = {
  id?: number;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserUpdateUsingPutResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserVO = {
  id?: number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
};
