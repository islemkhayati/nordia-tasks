
package nordia_tasks.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import nordia_tasks.entity.Member;
import nordia_tasks.repository.MemberRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

@Component
public class MemberDataLoader implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    public MemberDataLoader(MemberRepository memberRepository,
                            ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {

        if (memberRepository.count() == 0) {
            InputStream input = new ClassPathResource("members.json")
                    .getInputStream();

            List<Member> members = objectMapper.readValue(
                    input,
                    new TypeReference<List<Member>>() {}
            );

            memberRepository.saveAll(members);
            input.close();

            System.out.println("Members imported: " + members.size());
        }
    }
}
