#import <Foundation/Foundation.h>
#import <StudyCore/StudyCore.h>

// Godot iOS export plugin lifecycle. No game or grading rules belong here.
// The main run loop owns both the Kotlin service and the mailbox timer.
static StudyCoreStudyService *studyService;
static NSTimer *studyTimer;
static NSString *bridgeDirectory;

void initialize_study_bridge() {
    studyService = [StudyCoreStudyService new];
    NSString *documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
    bridgeDirectory = [documents stringByAppendingPathComponent:@"study-bridge"];
    NSFileManager *files = NSFileManager.defaultManager;
    [files createDirectoryAtPath:bridgeDirectory withIntermediateDirectories:YES attributes:nil error:nil];
    for (NSString *name in @[@"request.json", @"response.json", @"ready.json"]) {
        [files removeItemAtPath:[bridgeDirectory stringByAppendingPathComponent:name] error:nil];
    }
    [@"{\"version\":1}" writeToFile:[bridgeDirectory stringByAppendingPathComponent:@"ready.json"]
                              atomically:YES encoding:NSUTF8StringEncoding error:nil];
    studyTimer = [NSTimer timerWithTimeInterval:0.05 repeats:YES block:^(NSTimer *timer) {
        @autoreleasepool {
            NSString *requestPath = [bridgeDirectory stringByAppendingPathComponent:@"request.json"];
            if (![files fileExistsAtPath:requestPath]) return;
            NSString *request = [NSString stringWithContentsOfFile:requestPath encoding:NSUTF8StringEncoding error:nil];
            if (!request) return;
            NSString *response = [studyService exchangeRequestJson:request];
            [files removeItemAtPath:requestPath error:nil];
            NSError *error;
            [response writeToFile:[bridgeDirectory stringByAppendingPathComponent:@"response.json"]
                       atomically:YES encoding:NSUTF8StringEncoding error:&error];
            if (error) NSLog(@"StudyBridge: response write failed: %@", error);
        }
    }];
    [[NSRunLoop mainRunLoop] addTimer:studyTimer forMode:NSRunLoopCommonModes];
    NSLog(@"StudyBridge: Kotlin service ready");
}

void deinitialize_study_bridge() {
    [studyTimer invalidate];
    studyTimer = nil;
    [NSFileManager.defaultManager removeItemAtPath:[bridgeDirectory stringByAppendingPathComponent:@"ready.json"] error:nil];
    studyService = nil;
}
